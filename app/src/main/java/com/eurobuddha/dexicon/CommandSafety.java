package com.eurobuddha.dexicon;

/** Validate the single-command boundary before any IPC or node-side mutation. */
final class CommandSafety {
    private CommandSafety() {}
    static String failure(String command) {
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
