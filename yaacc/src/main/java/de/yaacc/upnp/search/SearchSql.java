/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.yaacc.upnp.search.SearchExpression.And;
import de.yaacc.upnp.search.SearchExpression.InvalidSearchException;
import de.yaacc.upnp.search.SearchExpression.Node;
import de.yaacc.upnp.search.SearchExpression.Op;
import de.yaacc.upnp.search.SearchExpression.Or;
import de.yaacc.upnp.search.SearchExpression.Rel;

/**
 * Turns a parsed search expression into the {@code WHERE} part of a media store query for one kind of items (music, videos,
 * images), pure Java. Column names come only from the {@link Source} (never from the text of the search), every value is a bound
 * argument, so what a control point sends can not change the query. A property that this server does not search at all is
 * refused (UPnP error 708); a property that exists but not for this kind of items (the artist of a video) simply matches nothing.
 */
public final class SearchSql {

    public static final String PROPERTY_CLASS = "upnp:class";

    /** The kind of items one query runs against. */
    public static final class Source {
        final String itemClass;
        final Map<String, List<String>> columns = new LinkedHashMap<>();

        /** @param itemClass the UPnP class of the items, for example {@code object.item.audioItem.musicTrack} */
        public Source(String itemClass) {
            this.itemClass = itemClass;
        }

        /** The property is looked for in these columns (any of them). */
        public Source column(String property, String... columnNames) {
            columns.put(property, Collections.unmodifiableList(Arrays.asList(columnNames)));
            return this;
        }
    }

    /** The condition: a constant ({@link #TRUE}, {@link #FALSE}) or SQL with its arguments. */
    public static final class Where {
        public static final Where TRUE = new Where(null, Collections.<String>emptyList(), true);
        public static final Where FALSE = new Where(null, Collections.<String>emptyList(), false);

        public final String sql;
        public final List<String> args;
        private final boolean constant;

        private Where(String sql, List<String> args, boolean constant) {
            this.sql = sql;
            this.args = args;
            this.constant = constant;
        }

        public boolean isTrue() {
            return sql == null && constant;
        }

        public boolean isFalse() {
            return sql == null && !constant;
        }
    }

    private SearchSql() {
    }

    /**
     * @param supported the properties this server searches ({@code GetSearchCapabilities}); a property outside of it is refused
     * @throws InvalidSearchException a property or an operator that is not supported
     */
    public static Where build(Node node, Source source, java.util.Set<String> supported) throws InvalidSearchException {
        if (node == SearchExpression.ALL) {
            return Where.TRUE;
        }
        if (node instanceof And) {
            return and(build(((And) node).left, source, supported), build(((And) node).right, source, supported));
        }
        if (node instanceof Or) {
            return or(build(((Or) node).left, source, supported), build(((Or) node).right, source, supported));
        }
        return relation((Rel) node, source, supported);
    }

    private static Where and(Where a, Where b) {
        if (a.isFalse() || b.isFalse()) {
            return Where.FALSE;
        }
        if (a.isTrue()) {
            return b;
        }
        if (b.isTrue()) {
            return a;
        }
        return join(" AND ", a, b);
    }

    private static Where or(Where a, Where b) {
        if (a.isTrue() || b.isTrue()) {
            return Where.TRUE;
        }
        if (a.isFalse()) {
            return b;
        }
        if (b.isFalse()) {
            return a;
        }
        return join(" OR ", a, b);
    }

    private static Where join(String logic, Where a, Where b) {
        List<String> args = new ArrayList<>(a.args);
        args.addAll(b.args);
        return new Where("(" + a.sql + logic + b.sql + ")", args, true);
    }

    private static Where relation(Rel rel, Source source, java.util.Set<String> supported) throws InvalidSearchException {
        if (PROPERTY_CLASS.equals(rel.property)) {
            return classRelation(rel, source.itemClass);
        }
        if (!supported.contains(rel.property)) {
            throw new InvalidSearchException("unsupported property: " + rel.property);
        }
        if (rel.op == Op.LT || rel.op == Op.LE || rel.op == Op.GT || rel.op == Op.GE || rel.op == Op.DERIVED_FROM) {
            throw new InvalidSearchException("unsupported operator for " + rel.property);
        }
        List<String> columns = source.columns.get(rel.property);
        boolean negative = rel.op == Op.NEQ || rel.op == Op.DOES_NOT_CONTAIN || (rel.op == Op.EXISTS && rel.value.equals("false"));
        if (columns == null || columns.isEmpty()) {
            return negative ? Where.TRUE : Where.FALSE; // the items have no such property
        }
        Where result = null;
        for (String column : columns) {
            Where one = column(rel, column);
            result = result == null ? one : (negative ? and(result, one) : or(result, one));
        }
        return result;
    }

    private static Where column(Rel rel, String column) {
        switch (rel.op) {
            case CONTAINS:
                return new Where(column + " LIKE ? ESCAPE '\\'", Collections.singletonList("%" + escapeLike(rel.value) + "%"), true);
            case DOES_NOT_CONTAIN:
                return new Where("(" + column + " IS NULL OR " + column + " NOT LIKE ? ESCAPE '\\')",
                        Collections.singletonList("%" + escapeLike(rel.value) + "%"), true);
            case EQ:
                return new Where(column + " = ?", Collections.singletonList(rel.value), true);
            case NEQ:
                return new Where("(" + column + " IS NULL OR " + column + " != ?)", Collections.singletonList(rel.value), true);
            default: // EXISTS
                return rel.value.equals("true")
                        ? new Where("(" + column + " IS NOT NULL AND " + column + " != '')", Collections.<String>emptyList(), true)
                        : new Where("(" + column + " IS NULL OR " + column + " = '')", Collections.<String>emptyList(), true);
        }
    }

    /** {@code upnp:class} is the same for every item of a source: the relation is a constant. */
    private static Where classRelation(Rel rel, String itemClass) throws InvalidSearchException {
        String value = rel.value;
        switch (rel.op) {
            case EQ:
                return constant(itemClass.equals(value));
            case NEQ:
                return constant(!itemClass.equals(value));
            case DERIVED_FROM:
                return constant(itemClass.equals(value) || itemClass.startsWith(value + "."));
            case CONTAINS:
                return constant(itemClass.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)));
            case DOES_NOT_CONTAIN:
                return constant(!itemClass.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)));
            case EXISTS:
                return constant(value.equals("true"));
            default:
                throw new InvalidSearchException("unsupported operator for " + PROPERTY_CLASS);
        }
    }

    private static Where constant(boolean value) {
        return value ? Where.TRUE : Where.FALSE;
    }

    /** The text with {@code %}, {@code _} and the escape character {@code \} taken literally in a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
