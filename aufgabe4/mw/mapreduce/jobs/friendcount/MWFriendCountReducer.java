package mw.mapreduce.jobs.friendcount;

import mw.mapreduce.core.MWContext;
import mw.mapreduce.core.MWReducer;

public class MWFriendCountReducer extends MWReducer {

    @Override
    protected void reduce(String key, Iterable<String> values, MWContext<?> context) throws Exception {
        int friendCount = 0;
        String realName = null;

        for (String val : values) {
            if (val.startsWith("NAME:")) {
                // Wir haben den Klarnamen gefunden
                realName = val.substring(5); // "NAME:" abschneiden
            } else if (val.equals("COUNT")) {
                // Ein Freund
                friendCount++;
            }
        }

        // Wir geben nur was aus, wenn wir einen Namen haben (oder sollen wir IDs ausgeben, wenn kein Name da ist?
        // Aufgabe: "Format <Name>\t<#Freunde>". Also brauchen wir zwingend den Namen.)
        if (realName != null) {
            // Format: Name \t Anzahl
            // Da context.write(k, v) einen Tab dazwischen macht, nutzen wir:
            // Key = Name, Value = String.valueOf(friendCount)
            context.write(realName, String.valueOf(friendCount));
        }
    }
}