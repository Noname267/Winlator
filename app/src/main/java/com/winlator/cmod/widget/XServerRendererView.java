package com.winlator.cmod.widget;

import android.content.Context;
import android.view.SurfaceView;

import com.winlator.cmod.renderer.ViewTransformation;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.XServer;

public abstract class XServerRendererView extends SurfaceView {
    public final ViewTransformation viewTransformation = new ViewTransformation();
    protected final XServer xServer;

    protected XServerRendererView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
    }

    public abstract void onPause();
    public abstract void onResume();
    public abstract void onDestroy();

    public abstract void toggleFullscreen();
    public abstract boolean isFullscreen();

    public abstract void setCursorVisible(boolean visible);
    public abstract void setScreenOffsetYRelativeToCursor(boolean relative);
    public abstract void setMagnifierZoom(float zoom);
    public abstract float getMagnifierZoom();
    public abstract void setUnviewableWMClass(String wmClass);

    public abstract void onPointerMove(short x, short y);

    public abstract void addDirectContent(int windowId, Drawable drawable);
    public abstract void removeDirectContent(int windowId, int pixmapId);
    public abstract void setCompositeRedirected(int windowId, boolean redirected);
    public abstract void compositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY);
}
