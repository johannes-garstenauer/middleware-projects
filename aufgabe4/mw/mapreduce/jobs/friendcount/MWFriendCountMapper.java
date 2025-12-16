package mw.mapreduce.jobs.friendcount;

import mw.mapreduce.core.MWContext;
import mw.mapreduce.core.MWMapper;

public class MWFriendCountMapper extends MWMapper {

    @Override
    protected void map(String key, String value, MWContext<?> context) throws Exception {
        // Fall 1: Klarnmaen zu ID (Pipe-getrennt): <ID> \t | <NAME>
        // Fall 2: (Freundschaften aus Aufgabe 2): <ID_A> \t <ID_B>

        // Input vom Reader (der bei TAB trennt):
        // 1. Freundschaftszeile: Key="ID_A", Value="ID_B"
        // 2. Namenszeile: Key="ID", Value="|Real Name"

        if (value.startsWith("|")) { // Fall 1: Pipe Entfernen und als Name markieren
            String realName = value.substring(1).trim(); // Pipe entfernen
            context.write(key, "NAME:" + realName);
        } else { // Fall 2: Zähl-Marker emmitieren (Freundes ID irrelevant, wichtig nur dass es eine Freundschaft ist)
            context.write(key, "COUNT");
        }
    }
}