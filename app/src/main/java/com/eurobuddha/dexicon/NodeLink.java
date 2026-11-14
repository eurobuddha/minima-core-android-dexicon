package com.eurobuddha.dexicon;

import com.eurobuddha.minimaapi.MinimaAPIListener;

/**
 * The one way Dexicon reaches a Minima node. Standalone: {@link NodeTransport} (broadcast-Intent IPC through
 * NodeTransportService). Inside a host node app (Pandamonium): the host's in-process link, installed with
 * {@link HostMode#install}. Same callback contract either way - a reply JSON, or a {@code transporterror} failure with NO
 * status (a transport failure must never clear a pending signing marker).
 */
public interface NodeLink {
    void Command(String command, MinimaAPIListener cb);
    /** An admin-gated FILE action (PandaPools' node-backup capture). */
    void FileCommand(String action, String path, String newPath, android.net.Uri uri, MinimaAPIListener cb);
    void onDestroy();
}
