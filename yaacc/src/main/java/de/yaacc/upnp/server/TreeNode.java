package de.yaacc.upnp.server;


import java.util.LinkedList;

/**
 * TreeNode is a container for the value to represent a node on the TreeView
 */
public class TreeNode {

    private Object value;
    private TreeNode parent;
    private LinkedList<TreeNode> children;
    private int layoutId;
    private int level;
    private boolean isExpanded;
    private boolean isSelected;

    public TreeNode(Object value, int layoutId) {
        this.value = value;
        this.parent = null;
        this.children = new LinkedList<>();
        this.layoutId = layoutId;
        this.level = 0;
        this.isExpanded = false;
        this.isSelected = false;
    }

    public void addChild(TreeNode child) {
        child.setParent(this);
        child.setLevel(level + 1);
        children.add(child);
        updateNodeChildrenDepth(child);
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Object getValue() {
        return value;
    }

    public void setParent(TreeNode parent) {
        this.parent = parent;
    }

    public TreeNode getParent() {
        return parent;
    }

    public LinkedList<TreeNode> getChildren() {
        return children;
    }

    public int getLayoutId() {
        return layoutId;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public void setExpanded(boolean expanded) {
        isExpanded = expanded;
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public boolean isSelected() {
        return isSelected;
    }

    private void updateNodeChildrenDepth(TreeNode node) {
        if (node.getChildren().isEmpty()) return;
        for (TreeNode child : node.getChildren()) {
            child.setLevel(node.getLevel() + 1);
            updateNodeChildrenDepth(child);
        }
    }
}

