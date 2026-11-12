package com.idlearc.companion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads pages using the same read-only endpoint as the official game client. */
public final class InventoryPageLoader {
    public interface Fetcher { String get(String path) throws Exception; }
    public static final class Result {
        public final List<String> pages = new ArrayList<>();
        public final List<Map<String, Object>> items = new ArrayList<>();
        public int total;
    }
    private InventoryPageLoader() {}

    public static Result load(Fetcher fetcher) throws Exception {
        Result result = new Result();
        String first = fetcher.get("/api/inventory");
        Map<String, Object> root = SimpleJson.parseObject(first);
        if (root == null || root.isEmpty()) throw new Exception("Пустой ответ инвентаря");
        Set<String> ids = new HashSet<>();
        append(root, result.items, ids);
        result.pages.add(first);
        Map<String, Object> pag = SimpleJson.asMap(root.get("characterItemsPaginated"));
        result.total = pag == null ? result.items.size() : SimpleJson.intVal(pag, "total", result.items.size());
        int last = pag == null ? 1 : SimpleJson.intVal(pag, "last_page", 1);
        if (pag != null && last <= 1) {
            int perPage = SimpleJson.intVal(pag, "per_page", 0);
            if (perPage > 0) last = Math.max(last, (result.total + perPage - 1) / perPage);
        }
        last = Math.min(last, 100); // Bound the number of requests for a malformed response.
        for (int page = 2; page <= last; page++) {
            try {
                String json = fetcher.get("/api/items-page/" + page);
                Map<String, Object> next = SimpleJson.parseObject(json);
                if (next == null || SimpleJson.intVal(next, "current_page", 0) != page
                        || SimpleJson.asList(next.get("data")) == null) break;
                if (append(next, result.items, ids) == 0) break;
                result.pages.add(json);
            } catch (Exception ignored) {
                // Keep successfully loaded pages and expose the partial count to the user.
                break;
            }
        }
        result.total = Math.max(result.total, result.items.size());
        return result;
    }

    public static int append(Map<String, Object> root, List<Map<String, Object>> items, Set<String> ids) {
        if (root == null) return 0;
        Map<String, Object> pag = SimpleJson.asMap(root.get("characterItemsPaginated"));
        List<Object> data = pag != null ? SimpleJson.asList(pag.get("data")) : SimpleJson.asList(root.get("data"));
        if (data == null) data = SimpleJson.asList(root.get("items"));
        if (data == null) return 0;
        int added = 0;
        for (Object o : data) {
            Map<String, Object> item = SimpleJson.asMap(o);
            if (item == null) continue;
            String id = SimpleJson.str(item, "id", "");
            if (!id.isEmpty() && !ids.add(id)) continue;
            items.add(item);
            added++;
        }
        return added;
    }
}
