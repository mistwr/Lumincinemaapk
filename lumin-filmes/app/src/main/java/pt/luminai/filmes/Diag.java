package pt.luminai.filmes;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Registo de diagnóstico (últimas 200 linhas), visível em TV e comando → Diagnóstico. */
final class Diag {
    private static final List<String> lines = new ArrayList<>();
    static synchronized void log(String s) {
        lines.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s);
        while (lines.size() > 200) lines.remove(0);
    }
    static synchronized String dump() {
        StringBuilder b = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) b.append(lines.get(i)).append('\n');
        return b.toString();
    }
}
