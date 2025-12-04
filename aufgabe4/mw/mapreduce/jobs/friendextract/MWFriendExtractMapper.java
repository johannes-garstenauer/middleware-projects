package mw.mapreduce.jobs.friendextract;

import mw.mapreduce.core.MWContext;
import mw.mapreduce.core.MWMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MWFriendExtractMapper extends MWMapper {

    // Regex für Owner (Sucht nach id="next" und extrahiert die ID oder den Usernamen am Ende der URL)
    // Beispiel: value="http://www.facebook.com/people/Karsten-Traum/100000331207501"
    private static final Pattern OWNER_PATTERN = Pattern.compile("id=\"next\".*?value=\".*?(?:people/[^/]+/)?([^/\"\\?]+)\"");

    // Regex für Freunde (Sucht nach rel="friend" und href)
    // Beispiel: href="http://de-de.facebook.com/people/Julia-Kabus/100000984618634" rel="friend"
    private static final Pattern FRIEND_PATTERN = Pattern.compile("href=\".*?facebook\\.com/(?:people/[^/]+/)?([^/\"\\?]+)\"\\s+rel=\"friend\"");

    @Override
    protected void map(String key, String value, MWContext<?> context) throws Exception {
        // 1. Owner ermitteln
        Matcher ownerMatcher = OWNER_PATTERN.matcher(value);
        String ownerId = null;
        if (ownerMatcher.find()) {
            ownerId = ownerMatcher.group(1);
        }

        if (ownerId == null) return; // Kein Owner gefunden, Profil überspringen

        // 2. Freunde ermitteln
        Matcher friendMatcher = FRIEND_PATTERN.matcher(value);
        while (friendMatcher.find()) {
            String friendId = friendMatcher.group(1);

            if (friendId != null && !friendId.equals(ownerId)) {
                // Ausgabe: <ID_A> <ID_B> und <ID_B> <ID_A>
                // Formatvorgabe der Aufgabe beachten: Schlüssel \t Wert
                context.write(ownerId, friendId);
                context.write(friendId, ownerId);
            }
        }
    }
}