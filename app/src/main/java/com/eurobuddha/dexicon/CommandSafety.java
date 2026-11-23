package com.eurobuddha.dexicon;

/** Validate the single-command boundary before any IPC or node-side mutation: the shared rule
 *  ({@link com.eurobuddha.account.CommandSafety}), the same in AtomiX and PandaPools. */
public final class CommandSafety {
    private CommandSafety() {}
    public static String failure(String command) { return com.eurobuddha.account.CommandSafety.failure(command); }
}
