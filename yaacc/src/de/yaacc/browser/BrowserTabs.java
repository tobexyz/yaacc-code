package de.yaacc.browser;

public enum BrowserTabs {
    SERVER,
    CONTENT,
    RECEIVER,
    PLAYER;

    public static BrowserTabs valueOf(int ordinal){
        for( BrowserTabs tab : values()){
            if(tab.ordinal() == ordinal){
                return tab;

            }
        }
        return null;
    }

}
