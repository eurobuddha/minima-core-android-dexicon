package com.eurobuddha.minimaapi;

public class MinimaAPIMessages {

    /**
     * BASE MINIMA CLASS
     */
    public static final String MINIMA_BASE_CLASS = "com.eurobuddha.minimacore";

    /**
     * BASE MESSAGES
     */

    //Register
    public static final String MINIMA_API_REGISTER              = MINIMA_BASE_CLASS +".REGISTER";
    public static final String MINIMA_API_PACKAGE_CLASS         = MINIMA_BASE_CLASS +".PACKAGE_CLASS";
    public static final String MINIMA_API_APP_UID               = MINIMA_BASE_CLASS +".APP_UID";
    public static final String MINIMA_API_REGISTER_MINIMAID     = MINIMA_BASE_CLASS +".REGISTER_MINIMAID";

    //Send a command to run
    public static final String MINIMA_API_CMD                   = MINIMA_BASE_CLASS +".CMD";
    public static final String MINIMA_API_CMD_ACTION            = MINIMA_BASE_CLASS +".CMD_ACTION";

    //Caller can consume a content:// file response for results too big for an Intent extra
    public static final String MINIMA_API_CMD_FILERESP          = MINIMA_BASE_CLASS +".CMD_FILERESP";

    //Send the response back
    public static final String MINIMA_API_RESPONSE              = MINIMA_BASE_CLASS +".RESPONSE";
    public static final String MINIMA_API_RESPONSE_ID           = MINIMA_BASE_CLASS +".RESPONSE_ID";
    public static final String MINIMA_API_RESPONSE_RESULT       = MINIMA_BASE_CLASS +".RESPONSE_RESULT";

    //Large response - payload is at this content:// URI instead of RESPONSE_RESULT
    public static final String MINIMA_API_RESPONSE_URI          = MINIMA_BASE_CLASS +".RESPONSE_URI";
    public static final String MINIMA_API_RESPONSE_LEN          = MINIMA_BASE_CLASS +".RESPONSE_LEN";

    //Send a global Broadcast Message
    public static final String MINIMA_API_NOTIFY                = MINIMA_BASE_CLASS +".NOTIFY";
    public static final String MINIMA_API_NOTIFY_DATA           = MINIMA_BASE_CLASS +".NOTIFY_DATA";

    //File bridge - list/get/put/mkdir/move/delete files in the node's base folder.
    //ADMIN-gated on the node side (a readable base folder includes backups). Responses
    //come back on the normal .RESPONSE channel; "get" replies carry a granted content:// uri.
    public static final String MINIMA_API_FILE                  = MINIMA_BASE_CLASS +".FILE";
    public static final String MINIMA_API_FILE_ACTION           = MINIMA_BASE_CLASS +".FILE_ACTION";
    public static final String MINIMA_API_FILE_PATH             = MINIMA_BASE_CLASS +".FILE_PATH";
    public static final String MINIMA_API_FILE_NEWPATH          = MINIMA_BASE_CLASS +".FILE_NEWPATH";
    public static final String MINIMA_API_FILE_URI              = MINIMA_BASE_CLASS +".FILE_URI";

    /**
     * EVERY CORE A COMPANION MAY TALK TO
     *
     * The constants above are the bears' dialect. The official Minima Core speaks the same
     * protocol under its own package prefix, so each node is {package, action/extra prefix}.
     */
    public static final String BLOCK_PACKAGE        = "com.eurobuddha.minimablock";
    public static final String PANDAMONIUM_PACKAGE  = "com.eurobuddha.pandamonium";
    public static final String OFFICIAL_PACKAGE     = "org.minima.core";
    public static final String LEGACY_PACKAGE       = "org.minimarex.minimacore";

    public static final String[][] NODES = {
            {MINIMA_BASE_CLASS,   MINIMA_BASE_CLASS},
            {BLOCK_PACKAGE,       MINIMA_BASE_CLASS},
            {PANDAMONIUM_PACKAGE, MINIMA_BASE_CLASS},
            {OFFICIAL_PACKAGE,    OFFICIAL_PACKAGE},
            {LEGACY_PACKAGE,      LEGACY_PACKAGE},
    };

    public static final String[] PREFIXES = {MINIMA_BASE_CLASS, OFFICIAL_PACKAGE, LEGACY_PACKAGE};

    /** The action/extra prefix a node package listens on (the bears share ours). */
    public static String prefixOf(String zNodePackage){
        for (String[] node : NODES) {
            if (node[0].equals(zNodePackage)) return node[1];
        }
        return MINIMA_BASE_CLASS;
    }

    /** The prefix of a core action like "org.minima.core.RESPONSE", or null if it is not one. */
    public static String prefixOfAction(String zAction){
        if (zAction == null) return null;
        for (String prefix : PREFIXES) {
            if (zAction.startsWith(prefix + ".") && zAction.indexOf('.', prefix.length() + 1) < 0) return prefix;
        }
        return null;
    }

    /** key("org.minima.core", "CMD") = "org.minima.core.CMD" */
    public static String key(String zPrefix, String zName){
        return zPrefix + "." + zName;
    }
}
