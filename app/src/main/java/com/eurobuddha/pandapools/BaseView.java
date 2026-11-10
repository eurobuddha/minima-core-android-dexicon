// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/main/java/com/eurobuddha/pandapools/BaseView.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.view.LayoutInflater;
import android.view.View;

/** A single tab page. Each tab holds an inflated root view and refreshes from wallet state. */
public abstract class BaseView {

    protected final MainActivity act;
    protected final View root;

    public BaseView(MainActivity a, int layoutRes) {
        act = a;
        root = LayoutInflater.from(a).inflate(layoutRes, null);
    }

    public View getRoot() {
        return root;
    }

    @SuppressWarnings("unchecked")
    protected <T extends View> T find(int id) {
        return (T) root.findViewById(id);
    }

    /** Re-render from current wallet state. Always called on the UI thread. */
    public abstract void refresh();

    /** Called when this tab becomes the visible page (tab selected). Default: no-op. */
    public void onShown() {}

    /** Called on a new chain block while the app is loaded. Default: no-op. */
    public void onNewBlock() {}

    /** Called when the host Activity is stopped (backgrounded). Cancel repeating work here. Default: no-op. */
    public void onStop() {}

    /** Called when the host Activity is destroyed. Cancel all callbacks / release refs here. Default: no-op. */
    public void onDestroy() {}

    /** True while this tab has a transaction building/posting — so the host can defer a theme recreate()
     *  (which would tear down NodeApi mid-CmdChain and strand the tx). Default: no-op. */
    public boolean isBusy() { return false; }
}
