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
package de.yaacc.upnp.server.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Load state, generation (stale results) and levels of a tree node. Plain JVM. */
public class TreeNodeTest {

    private static TreeNode folder(String name) {
        return new TreeNode(new TreeNodeData(name, "/" + name, false, true), 1);
    }

    private static List<TreeNode> children(String... names) {
        List<TreeNode> result = new ArrayList<>();
        for (String name : names) {
            result.add(folder(name));
        }
        return result;
    }

    @Test
    public void aNewFolderIsNotLoadedAndShowsTheExpandSymbol() {
        TreeNode node = folder("a");
        assertFalse(node.isLoaded());
        assertFalse(node.isLoading());
        assertFalse(node.isKnownEmpty());
    }

    @Test
    public void finishingTheCurrentLoadAddsTheChildrenBelowTheNode() {
        TreeNode node = folder("a");
        node.setLevel(2);
        int generation = node.startLoading();
        assertTrue(node.isLoading());

        List<TreeNode> loaded = children("x", "y", "z");
        assertTrue(node.finishLoading(generation, loaded));

        assertTrue(node.isLoaded());
        assertFalse(node.isLoading());
        assertEquals(3, node.getChildren().size());
        for (TreeNode child : node.getChildren()) {
            assertSame(node, child.getParent());
            assertEquals(3, child.getLevel());
        }
        assertEquals("x", node.getChildren().get(0).getValue().getName());
        assertEquals("z", node.getChildren().get(2).getValue().getName());
    }

    @Test
    public void aLoadedFolderWithoutChildrenIsKnownEmpty() {
        TreeNode node = folder("a");
        assertTrue(node.finishLoading(node.startLoading(), new ArrayList<>()));
        assertTrue(node.isKnownEmpty());
    }

    @Test
    public void aResultOfACancelledLoadIsIgnored() {
        TreeNode node = folder("a");
        int generation = node.startLoading();
        assertTrue(node.cancelLoading());
        assertFalse(node.isLoading());

        assertFalse(node.finishLoading(generation, children("x")));
        assertTrue(node.getChildren().isEmpty());
        assertFalse(node.isLoaded());
    }

    @Test
    public void aResultOfAnOlderLoadIsIgnoredAfterARestart() {
        TreeNode node = folder("a");
        int first = node.startLoading();
        node.cancelLoading();
        int second = node.startLoading();

        assertFalse(node.finishLoading(first, children("old")));
        assertTrue(node.isLoading());
        assertTrue(node.finishLoading(second, children("new")));
        assertEquals(1, node.getChildren().size());
        assertEquals("new", node.getChildren().get(0).getValue().getName());
    }

    @Test
    public void aResultCannotBeAppliedTwice() {
        TreeNode node = folder("a");
        int generation = node.startLoading();
        assertTrue(node.finishLoading(generation, children("x")));
        assertFalse(node.finishLoading(generation, children("x")));
        assertEquals(1, node.getChildren().size());
    }

    @Test
    public void cancelOnlyAppliesToARunningLoad() {
        TreeNode node = folder("a");
        assertFalse(node.cancelLoading());
        node.finishLoading(node.startLoading(), children("x"));
        assertFalse(node.cancelLoading());
        assertTrue(node.isLoaded());
    }

    @Test
    public void aFailedLoadLeavesTheFolderUnknownSoTheNextClickReadsAgain() {
        TreeNode node = folder("a");
        int generation = node.startLoading();
        assertTrue(node.failLoading(generation));
        assertFalse(node.isLoaded());
        assertFalse(node.isKnownEmpty());
        assertFalse(node.failLoading(generation));

        int again = node.startLoading();
        assertTrue(again != generation);
        assertTrue(node.finishLoading(again, children("x")));
    }

    @Test
    public void aStaleFailureDoesNotEndTheNewerLoad() {
        TreeNode node = folder("a");
        int first = node.startLoading();
        node.cancelLoading();
        node.startLoading();
        assertFalse(node.failLoading(first));
        assertTrue(node.isLoading());
    }

    @Test
    public void addChildrenFixesTheLevelsOfGrandchildren() {
        TreeNode node = folder("a");
        TreeNode child = folder("b");
        child.addChild(folder("c"));
        node.addChildren(Arrays.asList(child));
        assertEquals(1, child.getLevel());
        assertEquals(2, child.getChildren().get(0).getLevel());
    }
}
