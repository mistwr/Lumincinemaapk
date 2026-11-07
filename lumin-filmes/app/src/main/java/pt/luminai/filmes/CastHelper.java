package pt.luminai.filmes;

import android.app.Activity;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import androidx.mediarouter.app.MediaRouteChooserDialog;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.images.WebImage;

/** Chromecast / Google TV para vídeos diretos (filmes "Toca já" e Arquivo livre). */
final class CastHelper {
    private final Activity act;
    private CastContext ctx;
    private MediaInfo pending;

    CastHelper(Activity a) {
        act = a;
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(a) == ConnectionResult.SUCCESS) {
                ctx = CastContext.getSharedInstance(a);
                ctx.getSessionManager().addSessionManagerListener(listener, CastSession.class);
            }
        } catch (Exception e) { ctx = null; }
    }

    boolean available() { return ctx != null; }

    void choose() {
        if (ctx == null) return;
        try {
            MediaRouteChooserDialog d = new MediaRouteChooserDialog(new ContextThemeWrapper(act, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar));
            d.setRouteSelector(ctx.getMergedSelector());
            d.setTitle("Transmitir para");
            d.show();
        } catch (Exception e) {
            Toast.makeText(act, "Não foi possível procurar Chromecasts", Toast.LENGTH_SHORT).show();
        }
    }

    void cast(String url, String title, String poster) {
        if (ctx == null) return;
        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, title != null ? title : "LUMIN Filmes");
        md.putString(MediaMetadata.KEY_SUBTITLE, "LUMIN Filmes");
        if (poster != null && !poster.isEmpty()) md.addImage(new WebImage(Uri.parse(poster)));
        MediaInfo mi = new MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType("video/mp4").setMetadata(md).build();
        CastSession s = ctx.getSessionManager().getCurrentCastSession();
        if (s != null && s.isConnected()) load(s, mi);
        else { pending = mi; choose(); }
    }

    private void load(CastSession s, MediaInfo mi) {
        RemoteMediaClient c = s.getRemoteMediaClient();
        if (c == null) return;
        c.load(new MediaLoadRequestData.Builder().setMediaInfo(mi).setAutoplay(true).build());
        Toast.makeText(act, "📡 A transmitir: " + mi.getMetadata().getString(MediaMetadata.KEY_TITLE), Toast.LENGTH_SHORT).show();
    }

    private final SessionManagerListener<CastSession> listener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarted(CastSession s, String id) { if (pending != null) { load(s, pending); pending = null; } }
        @Override public void onSessionResumed(CastSession s, boolean b) { if (pending != null) { load(s, pending); pending = null; } }
        @Override public void onSessionStarting(CastSession s) {}
        @Override public void onSessionStartFailed(CastSession s, int e) { pending = null; }
        @Override public void onSessionEnding(CastSession s) {}
        @Override public void onSessionEnded(CastSession s, int e) {}
        @Override public void onSessionResuming(CastSession s, String id) {}
        @Override public void onSessionResumeFailed(CastSession s, int e) {}
        @Override public void onSessionSuspended(CastSession s, int r) {}
    };
}
