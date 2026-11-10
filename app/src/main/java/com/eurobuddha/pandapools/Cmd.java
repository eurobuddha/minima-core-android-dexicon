// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/main/java/com/eurobuddha/pandapools/Cmd.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.ArrayList;
import java.util.List;

/**
 * One parser for node-command parameters, shared by every guard that reads a command it is about to run.
 *
 * WHY ONE. Two guard layers used to parse the same command differently. {@code CmdChain} took the FIRST
 * matching token; {@code TxPost} kept the LAST. On a well-formed command they agree, so nothing ever went
 * wrong — but on a command carrying {@code coinid:} twice they disagree, and those two layers do different
 * jobs with the answer: TxPost locks the coin and authorises the signature, CmdChain verifies the coin at the
 * signature boundary. Disagreeing means authorising one coin and verifying another.
 *
 * WHY IT REFUSES RATHER THAN RESOLVES. There is no correct answer to "which coinid did they mean". A duplicate
 * parameter in a fund-moving command is a bug or an injection attempt, and picking either value would be
 * guessing about which coins get spent. So {@link #param} returns null, and callers must treat null as
 * "refuse this command" — the same fail-closed rule the rest of the signing path follows.
 */
final class Cmd {

    private Cmd() {}

    /**
     * The single value of {@code name:} in {@code command}, or null when it is absent, empty, or present more
     * than once. Null is never "no value" versus "bad value": both mean do not proceed.
     */
    static String param(String command, String name) {
        if (command == null || name == null || name.isEmpty()) return null;
        String want = name + ":";
        String found = null;
        for (String part : command.trim().split("\\s+")) {
            if (!part.startsWith(want)) continue;
            if (found != null) return null;                 // duplicated — refuse rather than pick one
            found = part.substring(want.length());
        }
        return (found == null || found.isEmpty()) ? null : found;
    }

    /** The name of the command (its first token), or null. */
    static String verb(String command) {
        if (command == null) return null;
        String c = command.trim();
        if (c.isEmpty()) return null;
        int sp = c.indexOf(' ');
        return sp < 0 ? c : c.substring(0, sp);
    }

    /** True when {@code command} is this verb, matched on a whole token so "txninputs" is not "txninput". */
    static boolean is(String command, String verb) {
        return verb != null && verb.equals(verb(command));
    }

    /**
     * Every {@code coinid:} across the {@code txninput} lines of a command list, in order. Returns null if any
     * input line is malformed — a missing or duplicated coinid — so a caller cannot silently proceed with a
     * partial view of what is being spent.
     */
    static List<String> inputCoinIds(List<String> cmds) {
        List<String> out = new ArrayList<>();
        if (cmds == null) return out;
        for (String c : cmds) {
            if (!is(c, "txninput")) continue;
            String id = param(c, "coinid");
            if (id == null) return null;
            out.add(id);
        }
        return out;
    }
}
