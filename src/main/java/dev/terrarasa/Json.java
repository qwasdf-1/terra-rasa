package dev.terrarasa;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared Gson instance for the library's durable files (manifests, journal). */
public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }
}
