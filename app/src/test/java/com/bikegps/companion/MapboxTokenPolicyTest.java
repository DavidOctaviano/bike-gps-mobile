package com.bikegps.companion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MapboxTokenPolicyTest {
  @Test public void acceptsOnlyPublicTokens() {
    assertTrue(MapboxTokenPolicy.isUsablePublicToken(testToken("pk.")));
    assertFalse(MapboxTokenPolicy.isUsablePublicToken(testToken("sk.")));
    assertFalse(MapboxTokenPolicy.isUsablePublicToken("YOUR_MAPBOX_ACCESS_TOKEN"));
    assertFalse(MapboxTokenPolicy.isUsablePublicToken("pk.short"));
  }

  @Test public void savedTokenOverridesBuildConfiguration() {
    String saved = testToken("pk.saved-");
    String built = testToken("pk.built-");
    assertEquals(saved, MapboxTokenPolicy.resolve(saved, built));
    assertEquals(built, MapboxTokenPolicy.resolve("invalid", built));
    assertEquals("", MapboxTokenPolicy.resolve("", ""));
  }

  private static String testToken(String prefix) {
    return prefix + "x".repeat(40);
  }
}
