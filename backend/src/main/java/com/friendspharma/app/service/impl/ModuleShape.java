package com.friendspharma.app.service.impl;

/**
 * What a row in {@code ACL_MODULE_INFO} actually is.
 *
 * <p>Three kinds share one table and are told apart by two columns, and until
 * this class existed each screen worked it out for itself — differently.
 *
 * <ul>
 *   <li><b>Page</b> — has a route. Granted in RBAC, appears in the menu.</li>
 *   <li><b>Heading</b> — no route, has children. A menu group. Nothing to grant:
 *       its visibility follows from whether anything inside it is visible.</li>
 *   <li><b>Capability</b> — no route, no children. Granted in RBAC like a page,
 *       but there is nowhere to navigate to, so it must never reach a menu.
 *       Notifications is the first of these.</li>
 * </ul>
 *
 * <p>The disagreement this settles: the role matrix called a row a heading when
 * it had children <em>and</em> no route, while the per-user permission screen
 * skipped anything with no route at all. Nothing exercised the difference until
 * a capability existed — which under the old rules could be granted to a role
 * and never to a person, and would have rendered in the sidebar as a link to a
 * page that does not exist.
 *
 * <p>Kept as free functions over the two fields rather than methods on the
 * entity, because whether a module has children is a fact about other rows and
 * the callers already know it — one from a child query, the other from a set of
 * parent ids. Passing it in keeps this decision away from the database.
 */
final class ModuleShape {

    private ModuleShape() {}

    /** True where the module has no page of its own. */
    static boolean routeless(String routePath) {
        return routePath == null || routePath.isBlank();
    }

    /**
     * A menu group: a heading with pages under it.
     *
     * <p>Not grantable. Requiring a permission row on the heading would mean
     * every new child needed its parent granted to each role as well, and
     * forgetting that hides a page the user is entitled to with nothing on
     * screen to say why.
     */
    static boolean isHeading(String routePath, boolean hasChildren) {
        return routeless(routePath) && hasChildren;
    }

    /**
     * A capability: a permission with no page behind it.
     *
     * <p>Grantable on both permission screens, and excluded from every menu.
     */
    static boolean isCapability(String routePath, boolean hasChildren) {
        return routeless(routePath) && !hasChildren;
    }
}
