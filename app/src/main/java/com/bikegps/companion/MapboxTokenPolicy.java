package com.bikegps.companion;

/** Validates Mapbox mobile tokens without ever accepting a secret token in the app. */
final class MapboxTokenPolicy {
  private MapboxTokenPolicy() { }

  static String resolve(String saved, String buildConfigured) {
    if (isUsablePublicToken(saved)) return saved.trim();
    if (isUsablePublicToken(buildConfigured)) return buildConfigured.trim();
    return "";
  }

  static boolean isUsablePublicToken(String value) {
    if (value == null) return false;
    String token = value.trim();
    if (!token.startsWith("pk.") || token.length() < 24) return false;
    if (token.contains("YOUR_") || token.contains("SEU_") || token.contains(" ")) return false;
    for (int index = 0; index < token.length(); index++) {
      if (Character.isWhitespace(token.charAt(index))) return false;
    }
    return true;
  }
}
