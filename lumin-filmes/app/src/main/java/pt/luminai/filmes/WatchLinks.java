package pt.luminai.filmes;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extrai os links oficiais (JustWatch click-out) da página "watch" do TMDB. */
final class WatchLinks {
    private static final Pattern HREF = Pattern.compile("href=\"(https://click\\.justwatch\\.com/a\\?[^\"]+)\"");
    private static final Pattern CX = Pattern.compile("[?&]cx=([^&]+)");

    static String resolve(String tmdbLink) throws Exception {
        if (tmdbLink == null || !tmdbLink.startsWith("https://www.themoviedb.org/")) return "[]";
        HttpURLConnection c = (HttpURLConnection) new URL(tmdbLink).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "pt-PT,pt;q=0.9");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        String html = out.toString("UTF-8");

        JSONArray res = new JSONArray();
        Set<String> seen = new HashSet<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String url = m.group(1).replace("&amp;", "&");
            Matcher cm = CX.matcher(url);
            if (!cm.find()) continue;
            try {
                String raw = new String(Base64.decode(cm.group(1), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), "UTF-8");
                JSONObject d = new JSONObject(raw).getJSONArray("data").getJSONObject(0).getJSONObject("data");
                String prov = d.optString("provider");
                String type = d.optString("monetizationType");
                String key = prov + "|" + type;
                if (seen.contains(key)) continue;   // fica só com a primeira (melhor qualidade)
                seen.add(key);
                JSONObject o = new JSONObject();
                o.put("provider", prov); o.put("type", type); o.put("jwId", d.optInt("providerId")); o.put("url", url);
                res.put(o);
            } catch (Exception ignored) {}
        }
        return res.toString();
    }
}
