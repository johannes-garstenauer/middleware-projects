package mw.mapreduce.jobs.friendextract;
import java.io.IOException;
import mw.mapreduce.reader.MWReader;
import mw.mapreduce.util.MWPair;
import mw.mapreduce.util.MWSplitTextFileInput;

public class MWFriendExtractReader implements MWReader<MWPair<String, String>> {

    private MWSplitTextFileInput input;
    private String bufferedLine = null; // Speichert die erste Zeile des nächsten Profils
    private boolean finished = false;

    public MWFriendExtractReader(String file, long start, long length) throws IOException {
        this.input = new MWSplitTextFileInput(file, start, length);

        // Wenn wir nicht am Anfang der Datei sind, müssen wir zum Anfang
        // des nächsten Profils springen (das aktuelle gehört zum vorherigen Split).
        if (start > 0) {
            while (true) {
                String line = input.readLine();
                if (line == null) {
                    // Datei zu Ende bevor ein neues Profil begann
                    finished = true;
                    break;
                }
                if (line.contains("<!DOCTYPE")) {
                    bufferedLine = line; // Das ist der Start unseres ersten Profils
                    break;
                }
            }
        }
    }

    @Override
    public MWPair<String, String> read() throws IOException {
        if (finished) return null;

        StringBuilder content = new StringBuilder();

        // Falls wir eine Zeile gebuffert haben (Start-Tag vom letzten Aufruf), nutzen wir sie
        if (bufferedLine != null) {
            content.append(bufferedLine).append("\n");
            bufferedLine = null;
        } else {
            // Sollte eigentlich nur beim allerersten Read passieren (bei start=0)
            String line = input.readLine();
            if (line == null) return null;
            content.append(line).append("\n");
        }

        // Lesen bis zum nächsten Profil-Start oder EOF
        while (true) {
            // forceReadLine nutzen, um auch über Blockgrenzen hinweg zu lesen
            String line = input.forceReadLine();

            if (line == null) {
                finished = true; // Dateiende erreicht
                break;
            }

            if (line.contains("<!DOCTYPE")) {
                // Nächstes Profil beginnt -> Stoppen und Zeile für nächsten Aufruf merken
                bufferedLine = line;
                break;
            }

            content.append(line).append("\n");

            // Optimierung: Wenn wir über das Split-Ende hinaus sind UND das Profil zu Ende ist
            // (hier erkennen wir das Ende erst durch den Start des nächsten), stoppen wir.
            // Der MWSplitTextFileInput hört von selbst auf 'readLine' null zurückzugeben,
            // wenn das Limit erreicht ist. 'forceReadLine' ignoriert das Limit.
        }

        // Wir geben den gesamten HTML-Inhalt als Value zurück. Key ist hier egal (null).
        return new MWPair<>(null, content.toString());
    }
}