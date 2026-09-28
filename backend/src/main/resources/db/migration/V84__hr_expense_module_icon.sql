/* ─────────────────────────────────────────────────────────────────────────────
   HR — the expense claim module's own icon.

   V83 registered MOD37 with icon_key 'advance', which is the icon Advance &
   Loan already uses. Two adjacent items in the same group drawn with the same
   glyph is a menu people misread, and the sidebar reads its icon from this
   column rather than from the route.

   A separate migration rather than an edit to V83, because V83 has been applied
   and an applied migration is not changed. One statement, forward only, and it
   touches nothing but the icon.
   ───────────────────────────────────────────────────────────────────────────── */

UPDATE pharma.acl_module_info
   SET icon_key = 'expenses',
       updated_by = 'V84',
       updated_date = CURRENT_TIMESTAMP
 WHERE module_code = 'MOD37'
   AND icon_key = 'advance';


/* ═══ Verification ══════════════════════════════════════════════════════════ */

SELECT module_code, module_name, route_path, icon_key
  FROM pharma.acl_module_info
 WHERE module_code IN ('MOD35', 'MOD37')
 ORDER BY module_code;
