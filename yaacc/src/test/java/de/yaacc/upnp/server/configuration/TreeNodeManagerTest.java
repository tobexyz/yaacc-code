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
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The visible list and the range each expand/collapse/remove reports (what the adapter hands to
 * notifyItemRangeInserted/Removed). Checked against a plain depth first flattening of the tree.
 * Plain JVM.
 */
public class TreeNodeManagerTest {

    private static TreeNode node(String name) {
        return new TreeNode(new TreeNodeData(name, "/" + name, false, true), 1);
    }

    private static TreeNode folderWithChildren(String name, int count) {
        TreeNode folder = node(name);
        List<TreeNode> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            children.add(node(name + "/" + i));
        }
        folder.addChildren(children);
        return folder;
    }

    private static TreeNodeManager managerWith(TreeNode... roots) {
        TreeNodeManager manager = new TreeNodeManager();
        List<TreeNode> list = new ArrayList<>();
        Collections.addAll(list, roots);
        manager.setTreeNodes(list);
        return manager;
    }

    private static List<TreeNode> flatten(List<TreeNode> roots) {
        List<TreeNode> result = new ArrayList<>();
        for (TreeNode root : roots) {
            flatten(root, result);
        }
        return result;
    }

    private static void flatten(TreeNode node, List<TreeNode> out) {
        out.add(node);
        if (node.isExpanded()) {
            for (TreeNode child : node.getChildren()) {
                flatten(child, out);
            }
        }
    }

    @Test
    public void expandInsertsAllChildrenAsOneRangeBehindTheNode() {
        TreeNode first = node("first");
        TreeNode big = folderWithChildren("big", 5000);
        TreeNode last = node("last");
        TreeNodeManager manager = managerWith(first, big, last);

        TreeNodeManager.Change change = manager.expand(big);

        assertEquals(1, change.position);
        assertEquals(2, change.start);
        assertEquals(5000, change.count);
        assertEquals(5003, manager.size());
        assertSame(first, manager.get(0));
        assertSame(big, manager.get(1));
        assertSame(big.getChildren().get(0), manager.get(2));
        assertSame(big.getChildren().get(4999), manager.get(5001));
        assertSame(last, manager.get(5002));
    }

    @Test
    public void expandingAgainOrAnUnknownNodeChangesNothing() {
        TreeNode big = folderWithChildren("big", 3);
        TreeNodeManager manager = managerWith(big);
        manager.expand(big);

        TreeNodeManager.Change again = manager.expand(big);
        assertEquals(0, again.count);
        assertEquals(4, manager.size());

        TreeNodeManager.Change unknown = manager.expand(node("elsewhere"));
        assertEquals(-1, unknown.position);
        assertEquals(0, unknown.count);
    }

    @Test
    public void collapseRemovesTheChildrenAndTheVisibleGrandchildren() {
        TreeNode root = folderWithChildren("r", 3);
        TreeNode middle = root.getChildren().get(1);
        middle.addChildren(new ArrayList<>(List.of(node("g1"), node("g2"))));
        TreeNodeManager manager = managerWith(root, node("after"));
        manager.expand(root);
        manager.expand(middle);
        assertEquals(7, manager.size());

        TreeNodeManager.Change change = manager.collapse(root);

        assertEquals(0, change.position);
        assertEquals(1, change.start);
        assertEquals(5, change.count);
        assertEquals(2, manager.size());
        assertFalse(root.isExpanded());
        assertTrue("the child keeps its state", middle.isExpanded());
    }

    @Test
    public void expandingAgainRestoresTheStateOfTheGrandchildren() {
        TreeNode root = folderWithChildren("r", 2);
        TreeNode child = root.getChildren().get(0);
        child.addChildren(new ArrayList<>(List.of(node("g1"), node("g2"), node("g3"))));
        TreeNodeManager manager = managerWith(root);
        manager.expand(root);
        manager.expand(child);
        manager.collapse(root);

        TreeNodeManager.Change change = manager.expand(root);

        assertEquals(5, change.count);
        assertEquals(6, manager.size());
        assertEquals(flatten(List.of(root)), new ArrayList<>(manager.getTreeNodes()));
    }

    @Test
    public void collapsingACollapsedNodeChangesNothing() {
        TreeNode root = folderWithChildren("r", 2);
        TreeNodeManager manager = managerWith(root);
        TreeNodeManager.Change change = manager.collapse(root);
        assertEquals(0, change.position);
        assertEquals(0, change.count);
        assertEquals(1, manager.size());
    }

    @Test
    public void removeTakesTheNodeAndItsVisibleDescendants() {
        TreeNode a = folderWithChildren("a", 2);
        TreeNode b = folderWithChildren("b", 4);
        TreeNode c = node("c");
        TreeNodeManager manager = managerWith(a, b, c);
        manager.expand(b);

        TreeNodeManager.Change change = manager.removeWithDescendants(b);

        assertEquals(1, change.start);
        assertEquals(5, change.count);
        assertEquals(2, manager.size());
        assertSame(a, manager.get(0));
        assertSame(c, manager.get(1));
    }

    @Test
    public void removeOfACollapsedNodeTakesOnlyTheNode() {
        TreeNode a = folderWithChildren("a", 2);
        TreeNodeManager manager = managerWith(a, node("c"));
        TreeNodeManager.Change change = manager.removeWithDescendants(a);
        assertEquals(0, change.start);
        assertEquals(1, change.count);
        assertEquals(1, manager.size());
    }

    @Test
    public void removeOfAnUnknownNodeChangesNothing() {
        TreeNodeManager manager = managerWith(node("a"));
        TreeNodeManager.Change change = manager.removeWithDescendants(node("b"));
        assertEquals(0, change.count);
        assertEquals(1, manager.size());
    }

    @Test
    public void randomExpandAndCollapseKeepTheVisibleListAndReportTheRightRange() {
        Random random = new Random(42);
        for (int round = 0; round < 50; round++) {
            List<TreeNode> roots = new ArrayList<>();
            List<TreeNode> all = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(4); i++) {
                TreeNode root = node("r" + i);
                roots.add(root);
                all.add(root);
                grow(root, random, 3, all);
            }
            TreeNodeManager manager = new TreeNodeManager();
            manager.setTreeNodes(new ArrayList<>(roots));

            for (int step = 0; step < 60; step++) {
                TreeNode target = all.get(random.nextInt(all.size()));
                // only operate on what is visible, like clicks do
                int position = manager.indexOf(target);
                if (position == -1 || target.getChildren().isEmpty()) {
                    continue;
                }
                List<TreeNode> before = new ArrayList<>(manager.getTreeNodes());
                boolean expand = !target.isExpanded();
                TreeNodeManager.Change change = expand ? manager.expand(target) : manager.collapse(target);

                assertEquals(position, change.position);
                assertEquals(position + 1, change.start);
                assertEquals(flatten(roots), new ArrayList<>(manager.getTreeNodes()));
                int delta = manager.size() - before.size();
                assertEquals(expand ? delta : -delta, change.count);
                // the rest of the list is untouched around the range
                assertEquals(before.subList(0, change.start), manager.getTreeNodes().subList(0, change.start));
                int tail = before.size() - (expand ? change.start : change.start + change.count);
                assertEquals(before.subList(before.size() - tail, before.size()),
                        manager.getTreeNodes().subList(manager.size() - tail, manager.size()));
            }
        }
    }

    private static void grow(TreeNode parent, Random random, int depth, List<TreeNode> all) {
        if (depth == 0) {
            return;
        }
        int count = random.nextInt(4);
        List<TreeNode> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            children.add(node(parent.getValue().getName() + "." + i));
        }
        parent.addChildren(children);
        for (TreeNode child : children) {
            all.add(child);
            grow(child, random, depth - 1, all);
        }
    }

    @Test
    public void indexOfAnUnknownNodeIsMinusOne() {
        assertEquals(-1, managerWith(node("a")).indexOf(node("a")));
    }
}
