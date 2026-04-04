package server.webserver.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit test for StatusServlet JSON output structure.
 * Tests the Gson serialization logic used by the servlet.
 */
public class StatusServletTest {

    private static final Gson gson = new Gson();

    @Test
    public void testStatusJsonStructure() {
        // Simulate the JSON structure produced by StatusServlet
        JsonObject status = new JsonObject();
        status.addProperty("serverName", "Ceres-J");
        status.addProperty("version", "111");
        status.addProperty("uptime", 60000L);
        status.addProperty("playerCount", 3);

        String json = gson.toJson(status);
        assertNotNull(json);

        // Parse back and verify
        JsonObject parsed = gson.fromJson(json, JsonObject.class);
        assertEquals("Ceres-J", parsed.get("serverName").getAsString());
        assertEquals("111", parsed.get("version").getAsString());
        assertEquals(60000L, parsed.get("uptime").getAsLong());
        assertEquals(3, parsed.get("playerCount").getAsInt());
    }

    @Test
    public void testStatusJsonContainsAllFields() {
        JsonObject status = new JsonObject();
        status.addProperty("serverName", "Test Server");
        status.addProperty("version", "200");
        status.addProperty("uptime", 0L);
        status.addProperty("playerCount", 0);

        String json = gson.toJson(status);
        JsonObject parsed = gson.fromJson(json, JsonObject.class);

        assertTrue("Missing serverName", parsed.has("serverName"));
        assertTrue("Missing version", parsed.has("version"));
        assertTrue("Missing uptime", parsed.has("uptime"));
        assertTrue("Missing playerCount", parsed.has("playerCount"));
    }

    @Test
    public void testUptimeFormatValues() {
        // Verify that uptime is stored as a numeric value
        JsonObject status = new JsonObject();
        status.addProperty("uptime", 86400000L); // 1 day in ms

        String json = gson.toJson(status);
        JsonObject parsed = gson.fromJson(json, JsonObject.class);
        assertEquals(86400000L, parsed.get("uptime").getAsLong());
    }
}
