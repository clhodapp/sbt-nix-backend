package example.javalib;

import org.apache.commons.text.WordUtils;

public final class Shout {
    private Shout() {}

    public static String shout(String s) {
        return WordUtils.capitalizeFully(s);
    }
}
