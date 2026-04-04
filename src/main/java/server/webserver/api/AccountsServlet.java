package server.webserver.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.gson.Gson;

import server.database.accounts.Account;
import server.database.accounts.AccountManager;

/**
 * GET /api/accounts
 * Returns JSON array of registered accounts (without passwords).
 */
public class AccountsServlet extends HttpServlet {

    private static final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        addCorsHeaders(resp);

        LinkedList<Account> accounts = AccountManager.getAccounts();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Account account : accounts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", account.getId());
            entry.put("username", account.getUsername());
            entry.put("status", account.getStatus());
            entry.put("characters", new int[]{
                account.getChar(0),
                account.getChar(1),
                account.getChar(2),
                account.getChar(3)
            });
            result.add(entry);
        }

        resp.getWriter().write(gson.toJson(result));
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        addCorsHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void addCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }
}
