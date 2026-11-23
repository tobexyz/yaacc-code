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
import java.util.List;

/**
 * The search criteria of a ContentDirectory {@code Search} action as a tree (UPnP ContentDirectory service, search criteria
 * grammar), pure Java: relations ({@code dc:title contains "x"}, {@code =}, {@code !=}, {@code <}, {@code <=}, {@code >}, {@code >=},
 * {@code doesNotContain}, {@code derivedfrom}, {@code exists true|false}) joined by {@code and} and {@code or} ({@code and} binds
 * stronger), brackets, and {@code *} for everything. Strings are in double quotes; a backslash escapes a backslash or a quote.
 * The text comes from the network: it is limited in size, depth and number of relations before anything is built from it.
 */
public final class SearchExpression {

    public static final int MAX_LENGTH = 8000;
    public static final int MAX_RELATIONS = 200;
    public static final int MAX_DEPTH = 24;

    /** A refused criteria: not valid, too big, or something this server does not do. */
    public static final class InvalidSearchException extends Exception {
        InvalidSearchException(String message) {
            super(message);
        }
    }

    public enum Op {
        EQ, NEQ, LT, LE, GT, GE, CONTAINS, DOES_NOT_CONTAIN, DERIVED_FROM, EXISTS
    }

    /** A node of the tree: {@link #ALL}, a {@link Rel}, an {@link And} or an {@link Or}. */
    public interface Node {
    }

    /** Everything matches ({@code *}). */
    public static final Node ALL = new Node() {
        @Override
        public String toString() {
            return "*";
        }
    };

    public static final class Rel implements Node {
        public final String property;
        public final Op op;
        /** The quoted string; for {@link Op#EXISTS} "true" or "false". */
        public final String value;

        Rel(String property, Op op, String value) {
            this.property = property;
            this.op = op;
            this.value = value;
        }

        @Override
        public String toString() {
            return property + " " + op + " " + value;
        }
    }

    public static final class And implements Node {
        public final Node left;
        public final Node right;

        And(Node left, Node right) {
            this.left = left;
            this.right = right;
        }
    }

    public static final class Or implements Node {
        public final Node left;
        public final Node right;

        Or(Node left, Node right) {
            this.left = left;
            this.right = right;
        }
    }

    private final List<String> tokens = new ArrayList<>();
    private final List<Boolean> quoted = new ArrayList<>();
    private int position;
    private int relations;

    private SearchExpression() {
    }

    /** @throws InvalidSearchException if the text is empty, longer than {@link #MAX_LENGTH}, malformed, too deep or has too many relations */
    public static Node parse(String criteria) throws InvalidSearchException {
        if (criteria == null || criteria.trim().isEmpty()) {
            throw new InvalidSearchException("empty search criteria");
        }
        if (criteria.length() > MAX_LENGTH) {
            throw new InvalidSearchException("search criteria too long");
        }
        String trimmed = criteria.trim();
        if (trimmed.equals("*")) {
            return ALL;
        }
        SearchExpression parser = new SearchExpression();
        parser.tokenize(trimmed);
        Node node = parser.parseOr(0);
        if (parser.position != parser.tokens.size()) {
            throw new InvalidSearchException("unexpected text after the expression");
        }
        return node;
    }

    // ---- tokens: brackets, quoted strings and words

    private void tokenize(String text) throws InvalidSearchException {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                quoted.add(false);
                i++;
            } else if (c == '"') {
                StringBuilder value = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < text.length()) {
                    char d = text.charAt(i);
                    if (d == '\\') {
                        if (i + 1 >= text.length()) {
                            throw new InvalidSearchException("unfinished escape");
                        }
                        value.append(text.charAt(i + 1));
                        i += 2;
                    } else if (d == '"') {
                        closed = true;
                        i++;
                        break;
                    } else {
                        value.append(d);
                        i++;
                    }
                }
                if (!closed) {
                    throw new InvalidSearchException("unfinished string");
                }
                tokens.add(value.toString());
                quoted.add(true);
            } else {
                int start = i;
                while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(' && text.charAt(i) != ')'
                        && text.charAt(i) != '"') {
                    i++;
                }
                tokens.add(text.substring(start, i));
                quoted.add(false);
            }
        }
    }

    // ---- grammar: or > and > bracket | relation

    private Node parseOr(int depth) throws InvalidSearchException {
        checkDepth(depth);
        Node left = parseAnd(depth);
        while (isWord("or")) {
            position++;
            left = new Or(left, parseAnd(depth));
        }
        return left;
    }

    private Node parseAnd(int depth) throws InvalidSearchException {
        Node left = parsePrimary(depth);
        while (isWord("and")) {
            position++;
            left = new And(left, parsePrimary(depth));
        }
        return left;
    }

    private Node parsePrimary(int depth) throws InvalidSearchException {
        if (position >= tokens.size()) {
            throw new InvalidSearchException("the expression ends too early");
        }
        if (!quoted.get(position) && tokens.get(position).equals("(")) {
            position++;
            Node inner = parseOr(depth + 1);
            if (position >= tokens.size() || quoted.get(position) || !tokens.get(position).equals(")")) {
                throw new InvalidSearchException("missing closing bracket");
            }
            position++;
            return inner;
        }
        return parseRelation();
    }

    private Node parseRelation() throws InvalidSearchException {
        if (++relations > MAX_RELATIONS) {
            throw new InvalidSearchException("too many relations");
        }
        String property = next("property");
        String operator = next("operator");
        Op op = operatorOf(operator);
        if (op == null) {
            throw new InvalidSearchException("unknown operator");
        }
        if (position >= tokens.size()) {
            throw new InvalidSearchException("missing value");
        }
        boolean valueQuoted = quoted.get(position);
        String value = tokens.get(position++);
        if (op == Op.EXISTS) {
            if (valueQuoted || !(value.equals("true") || value.equals("false"))) {
                throw new InvalidSearchException("exists needs true or false");
            }
        } else if (!valueQuoted) {
            throw new InvalidSearchException("the value must be in quotes");
        }
        if (property.isEmpty() || property.equals("(") || property.equals(")")) {
            throw new InvalidSearchException("bad property");
        }
        return new Rel(property, op, value);
    }

    private String next(String what) throws InvalidSearchException {
        if (position >= tokens.size() || quoted.get(position)) {
            throw new InvalidSearchException("missing " + what);
        }
        return tokens.get(position++);
    }

    private boolean isWord(String word) {
        return position < tokens.size() && !quoted.get(position) && tokens.get(position).equals(word);
    }

    private static void checkDepth(int depth) throws InvalidSearchException {
        if (depth > MAX_DEPTH) {
            throw new InvalidSearchException("brackets nested too deep");
        }
    }

    private static Op operatorOf(String text) {
        switch (text) {
            case "=":
                return Op.EQ;
            case "!=":
                return Op.NEQ;
            case "<":
                return Op.LT;
            case "<=":
                return Op.LE;
            case ">":
                return Op.GT;
            case ">=":
                return Op.GE;
            case "contains":
                return Op.CONTAINS;
            case "doesNotContain":
                return Op.DOES_NOT_CONTAIN;
            case "derivedfrom":
                return Op.DERIVED_FROM;
            case "exists":
                return Op.EXISTS;
            default:
                return null;
        }
    }
}
