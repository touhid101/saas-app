import React, { useState, useEffect } from "react";
import { AppProvider, useApp, CAP_GLOBAL_SEARCH } from "./lib/AppContext.jsx";
import { ConfirmProvider } from "./lib/ConfirmProvider.jsx";
import Sidebar from "./components/Sidebar.jsx";
import Topbar from "./components/Topbar.jsx";
import Dashboard from "./pages/Dashboard.jsx";
import MasterData from "./pages/MasterData.jsx";
import Purchase from "./pages/Purchase.jsx";
import Sales from "./pages/Sales.jsx";
import QuickSales from "./pages/QuickSales.jsx";
import SalesActivity from "./pages/SalesActivity.jsx";
import Delivery from "./pages/Delivery.jsx";
import Collection from "./pages/Collection.jsx";
import Requisition from "./pages/Requisition.jsx";
import InvoiceMerge from "./pages/InvoiceMerge.jsx";
import Inventory from "./pages/Inventory.jsx";
import Reports from "./pages/Reports.jsx";
import RBAC from "./pages/RBAC.jsx";
import BranchRBAC from "./pages/BranchRBAC.jsx";
import PasswordAdmin from "./pages/PasswordAdmin.jsx";
import ForcePasswordChange from "./pages/ForcePasswordChange.jsx";
import AIAgent from "./pages/AIAgent.jsx";
import SalesPerformance from "./pages/SalesPerformance.jsx";
import Subscription from "./pages/Subscription.jsx";
import Tenants from "./pages/Tenants.jsx";
import Catalogue from "./pages/Catalogue.jsx";
import BranchCatalogue from "./pages/BranchCatalogue.jsx";
import CatalogueMasters from "./pages/CatalogueMasters.jsx";
import PlatformConsole from "./pages/PlatformConsole.jsx";
import EcomCustomers from "./pages/EcomCustomers.jsx";
import EcomOrders from "./pages/EcomOrders.jsx";
import EcomPromotions from "./pages/EcomPromotions.jsx";
import SalesDiscount from "./pages/SalesDiscount.jsx";
import SalesReturn from "./pages/SalesReturn.jsx";
import PurchaseReturn from "./pages/PurchaseReturn.jsx";
import SupplierPayment from "./pages/SupplierPayment.jsx";
import ProductMaster from "./pages/ProductMaster.jsx";
import HrEmployees from "./pages/HrEmployees.jsx";
import HrAttendance from "./pages/HrAttendance.jsx";
import HrLeave from "./pages/HrLeave.jsx";
import HrAdvances from "./pages/HrAdvances.jsx";
import HrPayroll from "./pages/HrPayroll.jsx";
import HrExpenses from "./pages/HrExpenses.jsx";
import HrApprovals from "./pages/HrApprovals.jsx";
import Hris from "./pages/Hris.jsx";
import HrReports from "./pages/HrReports.jsx";
import AccDashboard from "./pages/accounts/AccDashboard.jsx";
import AccJournals from "./pages/accounts/AccJournals.jsx";
import AccLedger from "./pages/accounts/AccLedger.jsx";
import AccChart from "./pages/accounts/AccChart.jsx";
import AccParties from "./pages/accounts/AccParties.jsx";
import AccCash from "./pages/accounts/AccCash.jsx";
import AccDailyCash from "./pages/accounts/AccDailyCash.jsx";
import AccInvestors from "./pages/accounts/AccInvestors.jsx";
import AccProfit from "./pages/accounts/AccProfit.jsx";
import AccSettings from "./pages/accounts/AccSettings.jsx";
import AccReports from "./pages/accounts/AccReports.jsx";
import AccReconcile from "./pages/accounts/AccReconcile.jsx";
import Login from "./pages/Login.jsx";
import GlobalSearch from "./components/GlobalSearch.jsx";
import { ALL_REPORTS } from "./services/reportService.js";
import usePageRoute from "./lib/usePageRoute.js";
import useFavicon from "./lib/useFavicon.js";

const PAGE_META = {
  dashboard:       { title: "Dashboard",             subtitle: "Cross-branch business overview" },
  master:          { title: "Organisation & Parties",  subtitle: "Branches, products, packaging, staff and trading partners" },
  orgstructure:    { title: "Org Structure",           subtitle: "Organisations, companies and the geography they trade in" },
  productmaster:   { title: "Product Master",         subtitle: "Product catalogue — pricing, stock levels, batch & expiry tracking" },
  purchase:        { title: "Purchase Invoice",       subtitle: "Batch-wise purchasing with MNP/MRP costing" },
  purchasereturn:  { title: "Purchase Return",        subtitle: "Goods returned to the supplier, out of the batch they were received into" },
  supplierpayment: { title: "Supplier Payment",       subtitle: "Money out, outstanding balances, ageing and supplier performance" },
  sales:           { title: "Sales / Return",         subtitle: "Batch-wise selling with FEFO allocation" },
  activity:        { title: "Sales Activity",         subtitle: "Order lifecycle — search, filter, assign, collect" },
  salesperformance: { title: "Sales Performance",      subtitle: "Who sold what this month, against their target" },
  salesdiscount:   { title: "Sales Discount (Sales%)", subtitle: "Company & product-wise discount rules for Box, Strip, PCS and Special customers" },
  salesreturn:     { title: "Sales Return", subtitle: "Goods returned against an invoice, back into the batch they were sold from" },
  delivery:        { title: "Delivery Management",    subtitle: "Track and manage deliveries" },
  collection:      { title: "Collection",             subtitle: "Cash collection from delivery men" },
  requisition:     { title: "Smart Requisition",      subtitle: "Reorder & supplier approval workflow" },
  merge:           { title: "Invoice Merge",          subtitle: "Combine multiple customer invoices" },
  inventory:       { title: "Inventory",              subtitle: "Stock, batch, expiry & alert management" },
  /* Counted rather than written down: the number was 28 while the screen was
     showing 47, because adding a report and updating a caption are two acts and
     only one of them is obvious. */
  reports:         { title: "Reports",                subtitle: `Operational reporting suite — ${ALL_REPORTS.length} reports` },
  ai:              { title: "AI Copilot",             subtitle: "Inventory intelligence & purchase recommendations" },
  rbac:            { title: "RBAC Administration",    subtitle: "Roles, menus, permissions & users" },
  branchrbac:      { title: "Branch Access Control",  subtitle: "People at this pharmacy, and what they may do" },
  subscription:    { title: "Subscription",           subtitle: "Your plan, renewal and payment history" },
  tenants:         { title: "Pharmacies",             subtitle: "Applications, subscriptions and access" },
  catalogue:       { title: "Medicine Catalogue",     subtitle: "The shared list pharmacies import from" },
  branchcatalogue: { title: "Branch Medicine Catalogue", subtitle: "Add medicines from the shared list to your own products" },
  cataloguelists:  { title: "Catalogue Lists",         subtitle: "Manufacturers, generics, categories, units and packaging" },
  hris:            { title: "HRIS",              subtitle: "Manage your HR activities and track your requests" },
  hrmaster:        { title: "HR Master",         subtitle: "Departments and designations" },
  hrreports:       { title: "HR Reports",        subtitle: "People, attendance and leave, pay — read from the live tables" },
  accounts:        { title: "Accounts",           subtitle: "The books — every business transaction, posted as double entry" },
  accjournals:     { title: "Journals",           subtitle: "Every posting, automatic or manual, and its reversal" },
  accledger:       { title: "Ledger",             subtitle: "Account by account, with running balances and the trial balance" },
  acccoa:          { title: "Chart of Accounts",  subtitle: "The accounts the engine posts to, and what each is for" },
  accparties:      { title: "Receivables & Payables", subtitle: "Who owes the pharmacy and whom the pharmacy owes" },
  acccash:         { title: "Cash & Bank",        subtitle: "Till, petty cash, banks and wallets — positions and vouchers" },
  accdailycash:    { title: "Daily Cash",         subtitle: "Open the day, count the till, close with the difference explained" },
  accinvestors:    { title: "Investors",          subtitle: "Capital in, withdrawals out, and what each investor is owed" },
  accprofit:       { title: "Profit Distribution", subtitle: "Share a period's profit by policy — calculated, reviewed, approved, posted, paid" },
  accsettings:     { title: "Periods & Settings", subtitle: "Fiscal periods, posting policies, payment modes, tax rates and rules" },
  accreports:      { title: "Accounts Reports",   subtitle: "Statements, ledgers, ageing and cash books — read from the posted ledger" },
  accreconcile:    { title: "Reconciliation",     subtitle: "Events, checks against the business records, and backfill of history" },
  hr:              { title: "Human Resources",   subtitle: "Staff records, pay basis and departure" },
  attendance:      { title: "Attendance",         subtitle: "The daily register — what payroll counts a working month from" },
  leave:           { title: "Leave",              subtitle: "Requests, approvals and the balance each person has left" },
  advances:        { title: "Advance & Loan",     subtitle: "Money lent to staff, and how payroll takes it back" },
  payroll:         { title: "Payroll",            subtitle: "Calculate, review, approve, post and pay" },
  expenses:        { title: "Expense Claims",     subtitle: "What staff spent on the pharmacy's behalf, and getting it back" },
  approvals:       { title: "Approvals",          subtitle: "Everything waiting for a decision, and who signs it" },
  platform:        { title: "Platform",               subtitle: "Revenue, renewals and who has stopped trading" },
  ecompromotions:  { title: "E-Commerce Promotions", subtitle: "Coupon codes customers type at checkout: who may use them, where, and for how long" },
  ecomorders:      { title: "E-Commerce Orders",      subtitle: "What customers ordered online from this pharmacy — confirm, pack, dispatch, deliver" },
  ecomcustomers:   { title: "E-Commerce Customers",   subtitle: "Online registrations across every pharmacy: approve, set the pricing tier, suspend" },
};

/** Pages the URL may name. Anything else falls back to the dashboard. */
const isKnownPage = (p) =>
  p === "quicksales" || Object.prototype.hasOwnProperty.call(PAGE_META, p);


function ToastContainer() {
  const { toasts } = useApp();
  if (!toasts.length) return null;
  return (
    <div style={{ position: "fixed", bottom: 24, right: 24, zIndex: 300, display: "flex", flexDirection: "column", gap: 8 }}>
      {toasts.map(t => (
        <div key={t.id} style={{
          background: t.type === "error" ? "var(--clay)" : t.type === "warning" ? "var(--amber)" : "var(--ink)",
          color: "#fff", padding: "13px 18px", borderRadius: 10,
          fontSize: 13.5, boxShadow: "var(--shadow-lg)",
          display: "flex", alignItems: "center", gap: 10, minWidth: 280, maxWidth: 420,
        }}>
          <span>{t.message}</span>
        </div>
      ))}
    </div>
  );
}

function AppShell() {
  /* A 402 from anywhere means this pharmacy has stopped paying. One listener
     rather than a decision in every screen — thirty screens each deciding what
     to do about it is thirty chances to get it wrong. */
  const [subscriptionBlocked, setSubscriptionBlocked] = useState(false);
  useEffect(() => {
    const onBlocked = () => setSubscriptionBlocked(true);
    window.addEventListener("pharma:subscription-expired", onBlocked);
    return () => window.removeEventListener("pharma:subscription-expired", onBlocked);
  }, []);

  /* The address bar names the page, so a menu item can be a real link: opened
     in a new tab, bookmarked, or reloaded onto. `quicksales` is not in
     PAGE_META — it renders outside the app shell — so it is allowed here
     explicitly rather than being rejected as unknown. */
  const [page, setPage] = usePageRoute("dashboard", isKnownPage);

  /* The mobile navigation drawer. Held here rather than inside Sidebar because
     the control that opens it lives in Topbar — they are siblings, and this is
     their nearest common parent. */
  const [navOpen, setNavOpen] = React.useState(false);
  const closeNav = React.useCallback(() => setNavOpen(false), []);

  /* Choosing a page closes it. Without this the drawer stays open over the
     screen it just navigated to, so every tap needs a second one to see the
     result — which reads as the menu being stuck. */
  React.useEffect(() => { setNavOpen(false); }, [page]);
  const { searchOpen, setSearchOpen, currentUser, can, refreshHrCounts } = useApp();

  /* Re-count what is waiting whenever the page changes.
     A badge that only ever loads once is worse than none: approve the last
     leave request, walk back to the dashboard, and a red 1 still says somebody
     is waiting. Navigation is the moment a stale count would be seen, so it is
     the moment to refresh — and the call is skipped entirely for a user with no
     HR page in their menu.

     The first page is not a navigation. AppContext already counts once when the
     menu arrives, and firing here as well asked the server the same question
     twice on every login. */
  const countedFor = React.useRef(page);
  useEffect(() => {
    if (countedFor.current === page) return;
    countedFor.current = page;
    refreshHrCounts?.();
  }, [page, refreshHrCounts]);

  /* The tab carries the pharmacy's own mark, for the same reason the sidebar
     does: on a platform serving many of them, one company logo on every tab is
     the wrong answer for all but one customer. */
  useFavicon(currentUser?.branchLogoUrl);

  // Ctrl+K global search
  useEffect(() => {
    const handler = (e) => {
      // The shortcut is gated like the button. A hidden feature that a key
      // combination still opens is not hidden.
      if ((e.ctrlKey || e.metaKey) && e.key === "k") {
        e.preventDefault();
        if (can(CAP_GLOBAL_SEARCH)) setSearchOpen(true);
      }
      if (e.key === "Escape") setSearchOpen(false);
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [setSearchOpen, can]);

  if (page === "quicksales") {
    return (
      <>
        <QuickSales onExit={() => setPage("dashboard")} />
        <ToastContainer />
      </>
    );
  }

  const meta = PAGE_META[page] || { title: "Friends Pharma", subtitle: "" };

  // A pharmacy that has stopped paying gets one screen, not a menu of things
  // that all fail. The sidebar and topbar stay so they still know which shop
  // they are signed in to and can sign out.
  if (subscriptionBlocked) {
    return (
      <div className="app-shell">
        <Sidebar active="subscription" onNavigate={() => {}} navOpen={navOpen} onClose={closeNav} />
        <div className="main-col">
          <Topbar title="Subscription" subtitle="Renew to continue" onNavigate={() => {}}
                  onMenu={() => setNavOpen(true)} />
          <div style={{ flex: 1, overflow: "auto" }}>
            <Subscription blocking />
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="app-shell">
      <Sidebar active={page} onNavigate={setPage} navOpen={navOpen} onClose={closeNav} />
      <div className="main-col">
        <Topbar title={meta.title} subtitle={meta.subtitle} onNavigate={setPage}
                onMenu={() => setNavOpen(true)} />
        {/* overflow visible, not auto. This box grows with the page — .main-col
            has no fixed height — so it never scrolled; but overflow:auto still
            made it a scroll container, and every position:sticky inside a page
            measured itself against this box instead of the window. A sticky
            save bar at the foot of the register therefore sat at the end of
            the content, never in view. Sideways overflow is clipped by <html>. */}
        <div style={{ flex: 1, overflow: "visible", minWidth: 0 }}>
          {page === "dashboard"   && <Dashboard onNavigate={setPage} />}
          {page === "master"      && <MasterData page="master" permission="master" />}
          {page === "orgstructure" && <MasterData page="orgstructure" permission="orgstructure" />}
          {page === "productmaster" && <ProductMaster />}
          {page === "purchase"    && <Purchase />}
          {page === "purchasereturn"   && <PurchaseReturn />}
          {page === "supplierpayment"  && <SupplierPayment />}
          {page === "sales"       && <Sales />}
          {page === "activity"    && <SalesActivity />}
          {page === "salesperformance" && <SalesPerformance />}
          {page === "salesdiscount"    && <SalesDiscount />}
          {page === "salesreturn"      && <SalesReturn />}
          {page === "delivery"    && <Delivery />}
          {page === "collection"  && <Collection />}
          {page === "requisition" && <Requisition onNavigate={setPage} />}
          {page === "merge"       && <InvoiceMerge />}
          {page === "inventory"   && <Inventory onNavigate={setPage} />}
          {page === "reports"     && <Reports />}
          {page === "ai"          && <AIAgent onNavigate={setPage} />}
          {page === "rbac"        && <RBAC />}
          {page === "branchrbac"  && <BranchRBAC />}
          {page === "passwords"   && <PasswordAdmin />}
          {page === "subscription" && <Subscription />}
          {page === "tenants"     && <Tenants />}
          {page === "catalogue"   && <Catalogue />}
          {page === "branchcatalogue" && <BranchCatalogue />}
          {page === "cataloguelists" && <CatalogueMasters />}
          {page === "hr"          && <HrEmployees />}
          {page === "attendance"  && <HrAttendance />}
          {page === "leave"       && <HrLeave />}
          {page === "advances"    && <HrAdvances />}
          {page === "payroll"     && <HrPayroll />}
          {page === "expenses"    && <HrExpenses />}
          {page === "approvals"   && <HrApprovals onNavigate={setPage} />}
          {page === "hris"        && <Hris onNavigate={setPage} />}
          {page === "hrmaster"    && <MasterData page="hrmaster" permission="hrmaster" />}
          {page === "hrreports"   && <HrReports />}
          {page === "accounts"    && <AccDashboard onNavigate={setPage} />}
          {page === "accjournals" && <AccJournals />}
          {page === "accledger"   && <AccLedger />}
          {page === "acccoa"      && <AccChart />}
          {page === "accparties"  && <AccParties />}
          {page === "acccash"     && <AccCash />}
          {page === "accdailycash" && <AccDailyCash />}
          {page === "accinvestors" && <AccInvestors />}
          {page === "accprofit"   && <AccProfit />}
          {page === "accsettings" && <AccSettings />}
          {page === "accreports"  && <AccReports />}
          {page === "accreconcile" && <AccReconcile />}
          {page === "platform"    && <PlatformConsole onNavigate={setPage} />}
          {page === "ecomorders"    && <EcomOrders />}
          {page === "ecompromotions" && <EcomPromotions />}
          {page === "ecomcustomers" && <EcomCustomers />}
        </div>
      </div>
      {searchOpen && <GlobalSearch onClose={() => setSearchOpen(false)} onNavigate={setPage} />}
      <ToastContainer />
    </div>
  );
}

export default function App() {
  return (
    <AppProvider>
      <ConfirmProvider>
        <AuthGate />
      </ConfirmProvider>
    </AppProvider>
  );
}

function AuthGate() {
  const { isLoggedIn, mustChangePassword } = useApp();
  if (!isLoggedIn) return <Login />;
  /* [V71] Stands in place of the application, not over it. The server refuses
     every endpoint but the change itself while this flag is set, so rendering
     the shell would put a working-looking menu in front of a session that can
     do nothing. */
  if (mustChangePassword) return <ForcePasswordChange />;
  return <AppShell />;
}
