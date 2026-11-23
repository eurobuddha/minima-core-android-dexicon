package com.eurobuddha.account;

/**
 * The single-command boundary, checked before any IPC: stock Minima Core splits a command on ';' (CommandRunner
 * runMultiCommand) and runs every part, and a control character can end a parameter early. One copy for every app of the
 * family (Dexicon's own NodeApi / NodeTransportService, AtomiX's and PandaPools' NodeApi), vendored with the account.
 */
public final class CommandSafety {
    private CommandSafety() {}

    /** Why {@code command} must not be sent, or null when it is one plain command. */
    public static String failure(String command) {
        if (command == null || command.trim().isEmpty()) return "Empty node command.";
        if (command.length() > 100_000) return "Node command is too large.";
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == ';' || c < 32 || c == 127)
                return "Unsafe command data. Nothing was sent to the node.";
        }
        return null;
    }
}
