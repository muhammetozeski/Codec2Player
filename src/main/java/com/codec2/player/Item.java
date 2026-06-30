package com.codec2.player;

/** Calma listesi ogesi. */
public final class Item {
    /** mode bu degerse: codec2 degil, normal ses dosyasi (mp3/ogg/...). */
    public static final int MODE_AUDIO = -2;

    public String uri;
    public String name;
    public int mode = -1;
    public int durSec = -1;

    public Item() {}
    public Item(String uri, String name) { this.uri = uri; this.name = name; }
}
