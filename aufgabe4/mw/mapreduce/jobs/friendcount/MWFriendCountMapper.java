package mw.mapreduce.jobs.friendcount;

import mw.mapreduce.core.MWContext;
import mw.mapreduce.core.MWMapper;

public class MWFriendCountMapper extends MWMapper {

    @Override
    protected void map(String key, String value, MWContext<?> context) throws Exception {
        // Fall 1: Eingabe kommt vom Reader als Key=ID, Value=Name (Pipe-getrennt)
        // ABER: Der Standard MWKeyValueReader trennt nur bei TAB.
        // Die Datei enthält Zeilen wie "zchatata \t |Cde Ziliro Samuel Chatata" (laut Aufgabenstellung ist | ein Marker).

        // Lassen Sie uns die Eingabeformate analysieren:
        // Format A (Freundschaften aus Aufgabe 2): <ID_A> \t <ID_B>
        // Format B (Namen aus Aufgabe 3): <ID> \t <Name> (mit Pipe-Marker?)
        // Die Aufgabe sagt: "Die Namensdatensätze weisen dabei folgendes Format auf: <ID>\t <Name>".
        // Das Pipe-Zeichen ist Teil des Namens oder Markers?
        // Zitat: "Das Trennzeichen '|' dient in diesem Zusammenhang als Kennzeichnung..."

        // Wahrscheinlicher Input vom Reader (der bei TAB trennt):
        // 1. Freundschaftszeile: Key="ID_A", Value="ID_B"
        // 2. Namenszeile: Key="ID", Value="|Real Name" (da das Pipe das erste Zeichen nach dem Tab ist?)
        //    ODER: Key="ID |Real Name" (wenn kein Tab da ist, aber die Aufgabe sagt \t).

        // Wir prüfen einfach den Value.

        if (value.startsWith("|")) {
            // Es ist ein Name! Format: "|Name"
            // Wir entfernen die Pipe und markieren es als Name für den Reducer
            String realName = value.substring(1).trim(); // Pipe entfernen
            context.write(key, "NAME:" + realName);
        } else {
            // Es ist eine Freundschaft! Value ist die ID des Freundes.
            // Wir brauchen die ID des Freundes nicht wissen, nur DASS es einen gibt.
            // Wir emittieren einfach einen Zähl-Marker.
            context.write(key, "COUNT");
        }
    }
}