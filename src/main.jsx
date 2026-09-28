import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App.jsx";
import "./styles.css";
/* After styles.css on purpose: the responsive layer wins on equal specificity
   without having to inflate its selectors to get there. */
import "./responsive.css";
/* Last, so the header section is the final word on the bar and nothing earlier
   has to be edited to make room for it. */
import "./topbar.css";
/* The sidebar's branch identity block. Last, for the same reason as topbar.css. */
import "./brand.css";
/* The Quick Sales till, which was three fixed columns and unusable below
   ~1100px. Last, so it wins over the base .pos rules in styles.css. */
import "./quicksales-responsive.css";
import "./purchase-responsive.css";
/* Purchase lines fit without a sideways scrollbar; the totals rail scrolls
   inside itself. After purchase-responsive.css so it wins on equal selectors. */
import "./purchase-polish.css";
/* The five HR screens. Self-contained under .hr-*, and it borrows the till's
   breakpoints and bottom-bar pattern rather than inventing a third one. */
import "./hr.css";
/* The Accounts screens. Everything under .acc-*, built on the HR kit's
   page frame, tables and breakpoints; this file only adds what a ledger
   needs — money columns that line up, figures, the day-sheet count grid. */
import "./accounts.css";
/* The e-commerce console screens: a few classes on top of the HR kit. */
import "./ecom-console.css";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
