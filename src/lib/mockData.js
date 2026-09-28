/**
 * Friends Pharma — Mock Data Layer
 * Mirrors the Oracle schema from Pharma_Database.sql + f110.sql APEX logic
 * Priority: Database structure > APEX behavior > SRS > Prompt enhancements
 */
import { makeRng, pick, pickN, int, round2, addDays, fmtDate } from "./rng.js";

const rng = makeRng(1337);
export const TODAY = new Date("2026-08-10");
export const TODAY_STR = fmtDate(TODAY);

/* ─── ORGANIZATION HIERARCHY ─────────────────────────────────────────────── */
export const ORGANIZATIONS = [
  { id: "ORG1", name: "Friends Pharma Group",      code: "FPG", status: "Active" },
  { id: "ORG2", name: "HealthCare Holdings Ltd.",   code: "HCH", status: "Active" },
];

/** Companies under an Organization (POS_COMPANY) */
export const ORG_COMPANIES = [
  { id: "OC1", name: "Friends Pharma Ltd.",         orgId: "ORG1", code: "FPL", status: "Active" },
  { id: "OC2", name: "Friends Distribution Co.",    orgId: "ORG1", code: "FDC", status: "Active" },
  { id: "OC3", name: "HealthCare Retail Ltd.",       orgId: "ORG2", code: "HRL", status: "Active" },
];

export const REGIONS = [
  { id: "R1", name: "Dhaka" },    { id: "R2", name: "Chattogram" },
  { id: "R3", name: "Khulna" },   { id: "R4", name: "Rajshahi" },
  { id: "R5", name: "Sylhet" },   { id: "R6", name: "Barishal" },
];

export const DIVISIONS = [
  { id: "DIV1", name: "Dhaka Division",      regionId: "R1" },
  { id: "DIV2", name: "Chattogram Division", regionId: "R2" },
  { id: "DIV3", name: "Khulna Division",     regionId: "R3" },
  { id: "DIV4", name: "Rajshahi Division",   regionId: "R4" },
  { id: "DIV5", name: "Sylhet Division",     regionId: "R5" },
  { id: "DIV6", name: "Barishal Division",   regionId: "R6" },
];

export const DISTRICTS = [
  { id: "DS1", name: "Dhaka",       divisionId: "DIV1" },
  { id: "DS2", name: "Narayanganj", divisionId: "DIV1" },
  { id: "DS3", name: "Gazipur",     divisionId: "DIV1" },
  { id: "DS4", name: "Chattogram",  divisionId: "DIV2" },
  { id: "DS5", name: "Cox's Bazar", divisionId: "DIV2" },
  { id: "DS6", name: "Khulna",      divisionId: "DIV3" },
  { id: "DS7", name: "Rajshahi",    divisionId: "DIV4" },
  { id: "DS8", name: "Sylhet",      divisionId: "DIV5" },
];

export const THANAS = [
  { id: "TH1", name: "Mirpur",     districtId: "DS1" },
  { id: "TH2", name: "Uttara",     districtId: "DS1" },
  { id: "TH3", name: "Dhanmondi",  districtId: "DS1" },
  { id: "TH4", name: "Gulshan",    districtId: "DS1" },
  { id: "TH5", name: "Agrabad",    districtId: "DS4" },
  { id: "TH6", name: "GEC",        districtId: "DS4" },
];

export const AREAS = [
  { id: "A1",  name: "Mirpur-10",       thanaId: "TH1" },
  { id: "A2",  name: "Mirpur-12",       thanaId: "TH1" },
  { id: "A3",  name: "Uttara Sector 7", thanaId: "TH2" },
  { id: "A4",  name: "Uttara Sector 11",thanaId: "TH2" },
  { id: "A5",  name: "Dhanmondi-27",    thanaId: "TH3" },
  { id: "A6",  name: "Gulshan-1",       thanaId: "TH4" },
  { id: "A7",  name: "GEC Circle",      thanaId: "TH6" },
  { id: "A8",  name: "Agrabad C/A",     thanaId: "TH5" },
  { id: "A9",  name: "Sonadanga",       thanaId: "TH6" },
  { id: "A10", name: "Shaheb Bazar",    thanaId: "TH6" },
];

/**
 * POS_BRANCH — has Organization + Company + Branch hierarchy
 * orgId      → ORGANIZATIONS
 * companyId  → ORG_COMPANIES
 * regionId   → REGIONS
 */
export const BRANCHES = [
  { id:"B01", name:"Dhaka - Mirpur",         orgId:"ORG1", companyId:"OC1", regionId:"R1", address:"House 12, Road 5, Mirpur-10, Dhaka",       status:"Active" },
  { id:"B02", name:"Dhaka - Uttara",          orgId:"ORG1", companyId:"OC1", regionId:"R1", address:"Plot 3, Sector 7, Uttara, Dhaka",           status:"Active" },
  { id:"B03", name:"Chattogram - GEC",        orgId:"ORG1", companyId:"OC1", regionId:"R2", address:"GEC Circle, Chattogram",                    status:"Active" },
  { id:"B04", name:"Chattogram - Agrabad",    orgId:"ORG1", companyId:"OC2", regionId:"R2", address:"Agrabad C/A, Chattogram",                   status:"Active" },
  { id:"B05", name:"Khulna - Sonadanga",      orgId:"ORG1", companyId:"OC2", regionId:"R3", address:"Sonadanga, Khulna",                         status:"Active" },
  { id:"B06", name:"Rajshahi - Shaheb Bazar", orgId:"ORG1", companyId:"OC2", regionId:"R4", address:"Shaheb Bazar, Rajshahi",                    status:"Active" },
  { id:"B07", name:"Sylhet - Zindabazar",     orgId:"ORG2", companyId:"OC3", regionId:"R5", address:"Zindabazar, Sylhet",                        status:"Active" },
  { id:"B08", name:"Barishal - Sadar",        orgId:"ORG2", companyId:"OC3", regionId:"R6", address:"Sadar Road, Barishal",                      status:"Active" },
];

/* ─── PRODUCT MASTER DATA ────────────────────────────────────────────────── */
export const CATEGORIES = [
  { id:"C1",  name:"Analgesic" },        { id:"C2",  name:"Antibiotic" },
  { id:"C3",  name:"Antacid / Gastro" }, { id:"C4",  name:"Vitamin & Supplement" },
  { id:"C5",  name:"Antihistamine" },    { id:"C6",  name:"Cardiac" },
  { id:"C7",  name:"Diabetic Care" },    { id:"C8",  name:"Respiratory" },
  { id:"C9",  name:"Dermatology" },      { id:"C10", name:"Pediatric" },
];

export const SUBCATEGORIES = [
  { id:"SC1",  name:"Tablet",      categoryId:"C1"  },
  { id:"SC2",  name:"Syrup",       categoryId:"C10" },
  { id:"SC3",  name:"Capsule",     categoryId:"C2"  },
  { id:"SC4",  name:"Injection",   categoryId:"C6"  },
  { id:"SC5",  name:"Ointment",    categoryId:"C9"  },
  { id:"SC6",  name:"Inhaler",     categoryId:"C8"  },
  { id:"SC7",  name:"Suspension",  categoryId:"C3"  },
  { id:"SC8",  name:"Eye Drops",   categoryId:"C9"  },
  { id:"SC9",  name:"Nasal Spray", categoryId:"C8"  },
  { id:"SC10", name:"Suppository", categoryId:"C10" },
  { id:"SC11", name:"Gel",         categoryId:"C1"  },
  { id:"SC12", name:"Drops",       categoryId:"C10" },
];

export const UNITS = [
  { id:"U1", name:"Tablet"  }, { id:"U2", name:"Capsule" },
  { id:"U3", name:"ML"      }, { id:"U4", name:"Gram"    },
  { id:"U5", name:"Piece"   }, { id:"U6", name:"Bottle"  },
];

/** Product manufacturer companies (POS_COMPANY) */
export const COMPANIES = [
  { id:"CO1", name:"Meridian Pharma"         },
  { id:"CO2", name:"Solace Labs"             },
  { id:"CO3", name:"Vantage Biotech"         },
  { id:"CO4", name:"Greenleaf Pharmaceuticals"},
  { id:"CO5", name:"PrimeCare Formulations"  },
  { id:"CO6", name:"NovaCure Laboratories"   },
  { id:"CO7", name:"Square Pharmaceuticals"  },
  { id:"CO8", name:"Beximco Pharma"          },
];

export const GENERICS = [
  { id:"G1",  name:"Paracetamol",        companyId:"CO1", description:"Analgesic & antipyretic",           status:"Active" },
  { id:"G2",  name:"Amoxicillin",        companyId:"CO2", description:"Broad-spectrum antibiotic",         status:"Active" },
  { id:"G3",  name:"Omeprazole",         companyId:"CO3", description:"Proton pump inhibitor",             status:"Active" },
  { id:"G4",  name:"Cetirizine",         companyId:"CO4", description:"Second-generation antihistamine",   status:"Active" },
  { id:"G5",  name:"Metformin",          companyId:"CO5", description:"Biguanide antidiabetic",            status:"Active" },
  { id:"G6",  name:"Atorvastatin",       companyId:"CO6", description:"HMG-CoA reductase inhibitor",       status:"Active" },
  { id:"G7",  name:"Salbutamol",         companyId:"CO7", description:"Short-acting beta2 agonist",        status:"Active" },
  { id:"G8",  name:"Ibuprofen",          companyId:"CO8", description:"NSAID analgesic",                   status:"Active" },
  { id:"G9",  name:"Vitamin C + Zinc",   companyId:"CO1", description:"Micronutrient supplement",          status:"Active" },
  { id:"G10", name:"Losartan",           companyId:"CO2", description:"Angiotensin II receptor blocker",   status:"Active" },
  { id:"G11", name:"Azithromycin",       companyId:"CO3", description:"Macrolide antibiotic",              status:"Active" },
  { id:"G12", name:"Domperidone",        companyId:"CO4", description:"Dopamine antagonist antiemetic",    status:"Active" },
  { id:"G13", name:"Pantoprazole",       companyId:"CO5", description:"Proton pump inhibitor",             status:"Active" },
  { id:"G14", name:"Amlodipine",         companyId:"CO6", description:"Calcium channel blocker",           status:"Active" },
  { id:"G15", name:"Montelukast",        companyId:"CO7", description:"Leukotriene receptor antagonist",   status:"Active" },
];

/** POS_LEAF — strip / blister packaging */
export const LEAFS = [
  { id:"L1", type:"Blister 10", pcsPerLeaf:10, status:"Active" },
  { id:"L2", type:"Blister 15", pcsPerLeaf:15, status:"Active" },
  { id:"L3", type:"Strip 8",    pcsPerLeaf:8,  status:"Active" },
  { id:"L4", type:"Strip 12",   pcsPerLeaf:12, status:"Active" },
  { id:"L5", type:"Strip 6",    pcsPerLeaf:6,  status:"Active" },
];

/** POS_BOX — box contains N leaves */
export const BOXES = [
  { id:"BX1", title:"Box 10×10 (100 pcs)",  leafId:"L1", leavesPerBox:10 },
  { id:"BX2", title:"Box 8×15 (120 pcs)",   leafId:"L2", leavesPerBox:8  },
  { id:"BX3", title:"Box 12×8 (96 pcs)",    leafId:"L3", leavesPerBox:12 },
  { id:"BX4", title:"Box 6×12 (72 pcs)",    leafId:"L4", leavesPerBox:6  },
  { id:"BX5", title:"Box 10×6 (60 pcs)",    leafId:"L5", leavesPerBox:10 },
];

/* ─── PRODUCT (100 items) — matches POS_PRODUCT schema exactly ──────────── */
// Columns: name, catId, subCatId, coId, genId, boxId, unitId, mrp, tp, vatPct, alertQty, discPct, imgColor
const PRODUCT_SEED = [
  /* name                    cat   sub   co    gen   box   unit  mrp    tp      vat   alert disc  color   */
  ["Napafex Tab 500mg",      "C1","SC1","CO1","G1","BX1","U1",  45,   38.70, 17.4, 40,   5,  "#2E6A62"],
  ["Napafex Syrup 120ml",    "C1","SC2","CO1","G1","BX3","U3",  62,   53.40, 17.4, 20,   0,  "#4C7A5E"],
  ["Amoxiclin Cap 500mg",    "C2","SC3","CO2","G2","BX2","U2",  85,   73.10,  0,   30,   3,  "#B8752B"],
  ["Amoxiclin Forte 875mg",  "C2","SC3","CO2","G2","BX1","U2", 148,  127.30,  0,   25,   3,  "#8F5A1E"],
  ["Gastrofix Cap 20mg",     "C3","SC3","CO3","G3","BX1","U2",  78,   67.10,  5,   30,   2,  "#2E7D8A"],
  ["Gastrofix DSR 30mg",     "C3","SC3","CO3","G3","BX2","U2", 120,  103.20,  5,   20,   2,  "#2E6A62"],
  ["Cetrizo Tab 10mg",       "C5","SC1","CO4","G4","BX1","U1",  42,   36.10,  5,   40,   0,  "#6B5EA8"],
  ["Cetrizo Syrup 60ml",     "C5","SC2","CO4","G4","BX3","U3",  58,   49.90,  5,   25,   0,  "#9B59B6"],
  ["Metfor Tab 500mg",       "C7","SC1","CO5","G5","BX1","U1",  35,   30.10,  0,   50,   5,  "#E67E22"],
  ["Metfor XR 1000mg",       "C7","SC1","CO5","G5","BX2","U1",  68,   58.50,  0,   30,   5,  "#D35400"],
  ["Atorstat Tab 10mg",      "C6","SC1","CO6","G6","BX1","U1",  55,   47.30,  5,   40,   3,  "#A8452E"],
  ["Atorstat Tab 20mg",      "C6","SC1","CO6","G6","BX2","U1",  98,   84.30,  5,   30,   3,  "#C0392B"],
  ["Salbunex Inhaler",       "C8","SC6","CO1","G7","BX4","U5", 285,  245.10,  5,   15,   0,  "#16A085"],
  ["Salbunex Neb 2.5mg",     "C8","SC9","CO1","G7","BX3","U3",  42,   36.10,  5,   30,   0,  "#1ABC9C"],
  ["Ibrofen Tab 400mg",      "C1","SC1","CO2","G8","BX1","U1",  38,   32.70,  5,   40,   5,  "#27AE60"],
  ["Ibrofen Gel 5%",         "C9","SC11","CO2","G8","BX4","U4", 85,  73.10,  5,   20,   0,  "#2ECC71"],
  ["Vizin-C Chewable",       "C4","SC1","CO3","G9","BX2","U1", 120,  103.20,  5,   25,   2,  "#F39C12"],
  ["Vizin-C Effervescent",   "C4","SC1","CO3","G9","BX3","U1", 145,  124.70,  5,   20,   2,  "#F1C40F"],
  ["Losarplus Tab 50mg",     "C6","SC1","CO4","G10","BX1","U1", 62,  53.40,  5,   35,   3,  "#8E44AD"],
  ["Losarplus H 50/12.5mg",  "C6","SC1","CO4","G10","BX2","U1", 88,  75.70,  5,   25,   3,  "#9B59B6"],
  ["Azimax Tab 250mg",       "C2","SC1","CO5","G11","BX5","U1", 65,  55.90,  0,   20,   0,  "#2980B9"],
  ["Azimax Tab 500mg",       "C2","SC1","CO5","G11","BX1","U1",120,  103.20,  0,   20,   0,  "#3498DB"],
  ["Domperin Tab 10mg",      "C3","SC1","CO6","G12","BX1","U1", 28,  24.10,  5,   40,   5,  "#1ABC9C"],
  ["Domperin Syrup 60ml",    "C3","SC2","CO6","G12","BX3","U3", 48,  41.30,  5,   25,   5,  "#16A085"],
  ["Pantozac Tab 40mg",      "C3","SC1","CO1","G13","BX2","U1", 55,  47.30,  5,   35,   2,  "#E74C3C"],
  ["Pantozac IV 40mg",       "C3","SC4","CO1","G13","BX4","U5",185,  159.10,  5,   10,   0,  "#C0392B"],
  ["Amlodip Tab 5mg",        "C6","SC1","CO2","G14","BX1","U1", 48,  41.30,  5,   40,   3,  "#2C3E50"],
  ["Amlodip Tab 10mg",       "C6","SC1","CO2","G14","BX2","U1", 75,  64.50,  5,   30,   3,  "#34495E"],
  ["Montair Tab 10mg",       "C8","SC1","CO3","G15","BX1","U1", 78,  67.10,  5,   30,   2,  "#27AE60"],
  ["Montair Granules 4mg",   "C10","SC7","CO3","G15","BX3","U1", 95, 81.70,  5,   20,   2,  "#229954"],
  ["Fluclox Cap 500mg",      "C2","SC3","CO4","G2","BX2","U2",  72,  61.90,  0,   25,   0,  "#B8752B"],
  ["Ciproflox Tab 500mg",    "C2","SC1","CO5","G2","BX1","U1",  65,  55.90,  0,   30,   0,  "#8F5A1E"],
  ["Cephalex Cap 500mg",     "C2","SC3","CO6","G2","BX2","U2",  88,  75.70,  0,   25,   0,  "#A8452E"],
  ["Ranitid Tab 150mg",      "C3","SC1","CO1","G3","BX1","U1",  22,  18.90,  5,   50,   5,  "#2E6A62"],
  ["Esomep Cap 40mg",        "C3","SC3","CO2","G3","BX1","U2",  95,  81.70,  5,   30,   2,  "#4C7A5E"],
  ["Fexo Tab 120mg",         "C5","SC1","CO3","G4","BX2","U1",  55,  47.30,  5,   35,   0,  "#6B5EA8"],
  ["Loratab Tab 10mg",       "C5","SC1","CO4","G4","BX1","U1",  32,  27.50,  5,   45,   0,  "#9B59B6"],
  ["Gluco Tab 500mg",        "C7","SC1","CO5","G5","BX1","U1",  28,  24.10,  0,   50,   5,  "#E67E22"],
  ["Diab Tab 1000mg",        "C7","SC1","CO6","G5","BX2","U1",  58,  49.90,  0,   30,   5,  "#D35400"],
  ["Simvast Tab 20mg",       "C6","SC1","CO1","G6","BX1","U1",  45,  38.70,  5,   40,   3,  "#A8452E"],
  ["Rosustat Tab 10mg",      "C6","SC1","CO2","G6","BX2","U1",  78,  67.10,  5,   30,   3,  "#C0392B"],
  ["Ventalin Inhaler",       "C8","SC6","CO3","G7","BX4","U5", 295,  253.70,  5,  15,   0,  "#16A085"],
  ["Theophyl Tab 200mg",     "C8","SC1","CO4","G7","BX1","U1",  38,  32.70,  5,   35,   0,  "#1ABC9C"],
  ["Diclo Tab 50mg",         "C1","SC1","CO5","G8","BX1","U1",  32,  27.50,  5,   45,   5,  "#27AE60"],
  ["Naprox Tab 500mg",       "C1","SC1","CO6","G8","BX2","U1",  42,  36.10,  5,   40,   5,  "#2ECC71"],
  ["VitD3 Cap 1000IU",       "C4","SC3","CO1","G9","BX1","U2",  65,  55.90,  5,   30,   2,  "#F39C12"],
  ["Calcitab 500mg",         "C4","SC1","CO2","G9","BX2","U1",  48,  41.30,  5,   35,   2,  "#F1C40F"],
  ["Telmisart Tab 40mg",     "C6","SC1","CO3","G10","BX1","U1", 55,  47.30,  5,   35,   3,  "#8E44AD"],
  ["Candesart Tab 8mg",      "C6","SC1","CO4","G10","BX2","U1", 72,  61.90,  5,   30,   3,  "#9B59B6"],
  ["Clarithro Tab 500mg",    "C2","SC1","CO5","G11","BX1","U1",135,  116.10,  0,  20,   0,  "#2980B9"],
  ["Doxycycl Cap 100mg",     "C2","SC3","CO6","G11","BX3","U2", 48,  41.30,  0,   30,   0,  "#3498DB"],
  ["Meto Tab 10mg",          "C6","SC1","CO1","G12","BX1","U1", 28,  24.10,  5,   45,   5,  "#1ABC9C"],
  ["Ondanse Tab 4mg",        "C3","SC1","CO2","G12","BX2","U1", 52,  44.70,  5,   35,   2,  "#16A085"],
  ["Lansopraz Cap 30mg",     "C3","SC3","CO3","G13","BX1","U2", 68,  58.50,  5,   30,   2,  "#E74C3C"],
  ["Rabepraz Tab 20mg",      "C3","SC1","CO4","G13","BX2","U1", 58,  49.90,  5,   35,   2,  "#C0392B"],
  ["Diltiazem Tab 60mg",     "C6","SC1","CO5","G14","BX1","U1", 45,  38.70,  5,   40,   3,  "#2C3E50"],
  ["Nifedip Tab 10mg",       "C6","SC1","CO6","G14","BX3","U1", 35,  30.10,  5,   45,   3,  "#34495E"],
  ["Singulair Tab 5mg",      "C8","SC1","CO1","G15","BX1","U1", 88,  75.70,  5,   25,   2,  "#27AE60"],
  ["Zafirlukast Tab 20mg",   "C8","SC1","CO2","G15","BX2","U1", 95,  81.70,  5,   20,   2,  "#229954"],
  ["Panadol Tab 500mg",      "C1","SC1","CO3","G1","BX1","U1",  40,  34.40, 17.4, 50,   5,  "#2E6A62"],
  ["Tylenol Tab 500mg",      "C1","SC1","CO4","G1","BX2","U1",  42,  36.10, 17.4, 45,   5,  "#4C7A5E"],
  ["Augmentin Tab 625mg",    "C2","SC1","CO5","G2","BX1","U1", 185,  159.10,  0,  20,   3,  "#B8752B"],
  ["Taxim-O Tab 200mg",      "C2","SC1","CO6","G2","BX3","U1", 125,  107.50,  0,  20,   3,  "#8F5A1E"],
  ["Nexium Cap 20mg",        "C3","SC3","CO1","G3","BX2","U2", 145,  124.70,  5,  25,   2,  "#2E7D8A"],
  ["Zantac Tab 150mg",       "C3","SC1","CO2","G3","BX1","U1",  25,  21.50,  5,  50,   5,  "#2E6A62"],
  ["Zyrtec Tab 10mg",        "C5","SC1","CO3","G4","BX2","U1",  48,  41.30,  5,  35,   0,  "#6B5EA8"],
  ["Benadryl Syrup 100ml",   "C5","SC2","CO4","G4","BX3","U3",  68,  58.50,  5,  20,   0,  "#9B59B6"],
  ["Glucophage Tab 500mg",   "C7","SC1","CO5","G5","BX1","U1",  32,  27.50,  0,  50,   5,  "#E67E22"],
  ["Acarbose Tab 50mg",      "C7","SC1","CO6","G5","BX2","U1",  78,  67.10,  0,  25,   5,  "#D35400"],
  ["Lipitor Tab 10mg",       "C6","SC1","CO1","G6","BX1","U1",  58,  49.90,  5,  35,   3,  "#A8452E"],
  ["Crestor Tab 10mg",       "C6","SC1","CO2","G6","BX2","U1",  88,  75.70,  5,  25,   3,  "#C0392B"],
  ["Proventil Inhaler",      "C8","SC6","CO3","G7","BX4","U5", 265,  228.10,  5, 15,   0,  "#16A085"],
  ["Aminophyllin Tab 100mg", "C8","SC1","CO4","G7","BX1","U1",  32,  27.50,  5, 40,   0,  "#1ABC9C"],
  ["Brufen Tab 400mg",       "C1","SC1","CO5","G8","BX2","U1",  35,  30.10,  5, 45,   5,  "#27AE60"],
  ["Voltaren Gel 1%",        "C9","SC11","CO6","G8","BX4","U4", 95,  81.70,  5, 15,   0,  "#2ECC71"],
  ["Redoxon Tab 1000mg",     "C4","SC1","CO1","G9","BX1","U1",  75,  64.50,  5, 25,   2,  "#F39C12"],
  ["Supradyn Tab",           "C4","SC1","CO2","G9","BX2","U1",  85,  73.10,  5, 20,   2,  "#F1C40F"],
  ["Cozaar Tab 50mg",        "C6","SC1","CO3","G10","BX1","U1", 65,  55.90,  5, 30,   3,  "#8E44AD"],
  ["Diovan Tab 80mg",        "C6","SC1","CO4","G10","BX2","U1", 88,  75.70,  5, 25,   3,  "#9B59B6"],
  ["Zithromax Tab 500mg",    "C2","SC1","CO5","G11","BX1","U1",128,  110.10,  0, 20,   0,  "#2980B9"],
  ["Erythro Tab 500mg",      "C2","SC1","CO6","G11","BX2","U1", 52,  44.70,  0, 30,   0,  "#3498DB"],
  ["Motilium Tab 10mg",      "C3","SC1","CO1","G12","BX1","U1", 32,  27.50,  5, 45,   5,  "#1ABC9C"],
  ["Reglan Tab 10mg",        "C3","SC1","CO2","G12","BX3","U1", 28,  24.10,  5, 50,   5,  "#16A085"],
  ["Pariet Tab 20mg",        "C3","SC1","CO3","G13","BX2","U1", 65,  55.90,  5, 30,   2,  "#E74C3C"],
  ["Dexlansop Cap 60mg",     "C3","SC3","CO4","G13","BX1","U2",125,  107.50,  5, 20,   2,  "#C0392B"],
  ["Norvasc Tab 5mg",        "C6","SC1","CO5","G14","BX1","U1", 52,  44.70,  5, 35,   3,  "#2C3E50"],
  ["Felodip Tab 5mg",        "C6","SC1","CO6","G14","BX2","U1", 45,  38.70,  5, 40,   3,  "#34495E"],
  ["Accolate Tab 20mg",      "C8","SC1","CO1","G15","BX1","U1", 92,  79.10,  5, 25,   2,  "#27AE60"],
  ["Zyflo Tab 600mg",        "C8","SC1","CO2","G15","BX2","U1",112,  96.30,  5, 20,   2,  "#229954"],
  ["Napa Extra Tab",         "C1","SC1","CO3","G1","BX1","U1",  55,  47.30, 17.4, 40,  5,  "#2E6A62"],
  ["Sergel Cap 20mg",        "C3","SC3","CO4","G3","BX2","U2",  78,  67.10,  5, 30,   2,  "#4C7A5E"],
  ["Telmikind Tab 40mg",     "C6","SC1","CO5","G10","BX1","U1", 58,  49.90,  5, 35,   3,  "#B8752B"],
  ["Cardivas Tab 6.25mg",    "C6","SC1","CO6","G14","BX2","U1", 68,  58.50,  5, 30,   3,  "#8F5A1E"],
  ["Maxpro Cap 40mg",        "C3","SC3","CO1","G13","BX1","U2", 72,  61.90,  5, 25,   2,  "#2E7D8A"],
  ["Respicort Inhaler",      "C8","SC6","CO2","G7","BX4","U5", 385,  331.10,  5, 10,   0,  "#16A085"],
  ["Allegro Tab 10mg",       "C5","SC1","CO3","G4","BX1","U1",  38,  32.70,  5, 40,   0,  "#6B5EA8"],
  ["Enbrel 25mg Inj",        "C6","SC4","CO4","G14","BX4","U5",2850, 2451.00, 5,  5,   0,  "#A8452E"],
  ["Lantus Insulin 10ml",    "C7","SC7","CO5","G5","BX4","U6", 785,  675.10,  5,  8,   0,  "#E67E22"],
  ["Novorapid Insulin 10ml", "C7","SC7","CO6","G5","BX4","U6", 785,  675.10,  5,  8,   0,  "#D35400"],
];

export const PRODUCTS = PRODUCT_SEED.map(
  ([name,catId,subCatId,coId,genId,boxId,unitId,mrp,tp,vatPct,alertQty,discPct,imgColor], i) => {
    const box   = BOXES.find(b => b.id === boxId);
    const leaf  = LEAFS.find(l => l.id === box.leafId);
    const pcsPerBox = leaf.pcsPerLeaf * box.leavesPerBox;
    const conversionPct = Math.round(((mrp - tp) / mrp) * 100);
    // IGTA = VAT amount per pcs at trade price (from APEX formula)
    const igta  = round2((tp * vatPct) / 100);
    // noOfPcs = pcsPerBox  (alias kept for clarity)
    const noOfPcs = pcsPerBox;
    // Sales price per leaf/strip (SALES_PER_LEAF from APEX)
    const salesPerLeaf   = leaf.pcsPerLeaf;
    const salesPerPcs    = 1;
    const salesPerSpc    = pcsPerBox;          // whole box
    const salesPerSpcLeaf= leaf.pcsPerLeaf;    // strip within box
    const salesPerSpcRet = 1;                  // return unit
    return {
      /* ─ identifiers ─ */
      id:             `P${i + 1}`,
      name,
      barcode:        String(7340000 + i),
      sku:            String(i + 1),
      /* ─ classification ─ */
      categoryId:     catId,
      subCategoryId:  subCatId,
      companyId:      coId,        // manufacturer
      genericId:      genId,
      /* ─ packaging ─ */
      unitId,
      unit:           UNITS.find(u => u.id === unitId)?.name || "Tablet",
      boxId,
      leafId:         leaf.id,
      pcsPerLeaf:     leaf.pcsPerLeaf,
      leavesPerBox:   box.leavesPerBox,
      pcsPerBox,
      noOfPcs,
      /* ─ pricing ─ */
      mrp:            round2(mrp),
      tp:             round2(tp),       // trade / purchase price
      vatPct,
      igta,
      discountPct:    discPct,          // default purchase discount %
      conversionPct,                    // (MRP-TP)/MRP %
      conversionRate: round2(mrp / tp), // MRP/TP ratio
      /* ─ sales percentages (from APEX POS_PRODUCT_INFO) ─ */
      salesPer:       12,
      salesPerLeaf,
      salesPerPcs,
      salesPerSpc,
      salesPerSpcLeaf,
      salesPerSpcRet,
      /* ─ stock thresholds ─ */
      alertQty,
      reorderLevel:   Math.round(alertQty * 1.5),
      safetyStock:    alertQty * 2,
      minStock:       Math.round(alertQty * 0.5),
      maxStock:       alertQty * 5,
      /* ─ flags ─ */
      prescriptionFlag: vatPct === 0 ? "N" : "Y",
      status:         i < 95 ? "Active" : "Inactive",
      /* ─ UI / display ─ */
      imgColor,        // used for POS card color swatch
      imageUrl: null,  // uploaded product image (base64 dataURL), null = use color swatch
      insertedDate:   fmtDate(addDays(TODAY, -int(rng, 30, 365))),
      insertedBy:     "USR001",
    };
  }
);


/* ─── CUSTOMERS ──────────────────────────────────────────────────────────── */
const CUSTOMER_ROWS = [
  ["City Life Hospital",        "Hospital","A1","B01","017",850000],
  ["Green Valley Clinic",       "Clinic",  "A2","B01","018",320000],
  ["Sunrise Pharmacy",          "Pharmacy","A3","B02","019",250000],
  ["Wellcare Diagnostics",      "Hospital","A4","B02","017",480000],
  ["Hope Medical Hall",         "Pharmacy","A5","B01","018",180000],
  ["Unity Health Center",       "Clinic",  "A6","B02","019",220000],
  ["Riverside Clinic",          "Clinic",  "A7","B03","017",360000],
  ["Prime Care Pharmacy",       "Pharmacy","A8","B03","018",210000],
  ["Metro General Store",       "Walk-in", "A1","B04","019", 50000],
  ["Family Health Point",       "Pharmacy","A2","B04","017",160000],
  ["Central Hospital Pharmacy", "Hospital","A3","B05","018",720000],
  ["Blue Sky Clinic",           "Clinic",  "A4","B05","019",290000],
  ["Harmony Medicare",          "Pharmacy","A5","B06","017",340000],
  ["Trust Pharmacy",            "Pharmacy","A6","B06","018",190000],
  ["Comfort Clinic",            "Clinic",  "A7","B07","019",260000],
  ["Nexus Diagnostics",         "Hospital","A8","B07","017",550000],
  ["Shanti Medical",            "Pharmacy","A9","B07","018",140000],
  ["Golden Life Pharmacy",      "Pharmacy","A10","B08","019",280000],
  ["New Horizon Clinic",        "Clinic",  "A1","B08","017",310000],
  ["Everwell Store",            "Walk-in", "A2","B01","018", 80000],
  ["Dhaka Medical Supplies",    "Hospital","A3","B02","019",920000],
  ["Caring Hearts Pharmacy",    "Pharmacy","A4","B03","017",230000],
  ["LifeCare Dispensary",       "Pharmacy","A5","B04","018",170000],
  ["MediFirst Clinic",          "Clinic",  "A6","B05","019",410000],
  ["SafeHealth Center",         "Clinic",  "A7","B06","017",380000],
  ["QuickMeds Pharmacy",        "Pharmacy","A8","B07","018",200000],
  ["AlHamra Pharmacy",          "Pharmacy","A9","B08","019",240000],
  ["Prohealth Diagnostics",     "Hospital","A10","B01","017",680000],
  ["Popular Medical Hall",      "Pharmacy","A1","B02","018",350000],
  ["Wellfit Pharmacy",          "Walk-in", "A2","B03","019", 90000],
  ["Ibn Sina Pharmacy",         "Pharmacy","A3","B04","017",420000],
  ["Medserve Clinic",           "Clinic",  "A4","B05","018",270000],
  ["CityMed Hospital",          "Hospital","A5","B06","019",880000],
  ["WellPharm Dispensary",      "Pharmacy","A6","B07","017",150000],
  ["NovaCure Pharmacy",         "Pharmacy","A7","B08","018",210000],
  ["FreshCare Clinic",          "Clinic",  "A8","B01","019",330000],
  ["PharmaPlus Store",          "Pharmacy","A9","B02","017",180000],
  ["MedCentral Hospital",       "Hospital","A10","B03","018",760000],
  ["HerbalMed Pharmacy",        "Pharmacy","A1","B04","019",120000],
  ["ZenCare Clinic",            "Clinic",  "A2","B05","017",250000],
  ["AlRahim Pharmacy",          "Pharmacy","A3","B06","018",160000],
  ["HealthBridge Diagnostics",  "Hospital","A4","B07","019",590000],
  ["MediQuick Store",           "Walk-in", "A5","B08","017", 60000],
  ["Sunrise Medical Hall",      "Pharmacy","A6","B01","018",280000],
  ["TruHealth Clinic",          "Clinic",  "A7","B02","019",360000],
  ["BrightLife Pharmacy",       "Pharmacy","A8","B03","017",190000],
  ["MedEase Dispensary",        "Pharmacy","A9","B04","018",220000],
  ["Omega Healthcare",          "Hospital","A10","B05","019",640000],
  ["Greenway Pharmacy",         "Pharmacy","A1","B06","017",210000],
  ["HealWell Clinic",           "Clinic",  "A2","B07","018",300000],
];

export const CUSTOMERS = CUSTOMER_ROWS.map(([name,type,areaId,branchId,prefix,creditLimit],i) => ({
  id:            `CU${String(i+1).padStart(3,"0")}`,
  code:          `CUST-${String(i+1).padStart(4,"0")}`,
  name, type, areaId, branchId,
  orgId:         BRANCHES.find(b=>b.id===branchId)?.orgId  || "ORG1",
  companyId:     BRANCHES.find(b=>b.id===branchId)?.companyId || "OC1",
  phone:         `${prefix}${int(rng,10000000,99999999)}`,
  email:         `${name.toLowerCase().replace(/\s+/g,".")}@example.com`,
  address:       `House ${int(rng,1,99)}, Road ${int(rng,1,20)}, ${AREAS.find(a=>a.id===areaId)?.name||"Dhaka"}`,
  creditLimit:   round2(creditLimit),
  openingBalance:round2(int(rng,0,50000)),
  status:        i<48?"Active":"Inactive",
  insertedDate:  fmtDate(addDays(TODAY,-int(rng,30,720))),
}));


/* ─── SUPPLIERS ──────────────────────────────────────────────────────────── */
const SUPPLIER_ROWS = [
  ["Meridian Distribution Ltd.", "CO1","B01","Mirpur, Dhaka",       "017"],
  ["Solace Supply Chain",        "CO2","B02","Gulshan, Dhaka",       "018"],
  ["Vantage Wholesale",          "CO3","B03","Agrabad, Chattogram",  "019"],
  ["Greenleaf Trading Co.",      "CO4","B04","Khulna Sadar",         "017"],
  ["PrimeCare Distributors",     "CO5","B05","Rajshahi City",        "018"],
  ["NovaCure Logistics",         "CO6","B06","Zindabazar, Sylhet",   "019"],
  ["National Pharma Depot",      "CO7","B01","Tejgaon, Dhaka",       "017"],
  ["United Drug House",          "CO8","B02","Motijheel, Dhaka",     "018"],
  ["Eastern Pharma Suppliers",   "CO1","B03","Narayanganj",          "019"],
  ["HealthFirst Distributors",   "CO2","B04","Dhanmondi, Dhaka",     "017"],
  ["Atlas Pharma Agency",        "CO3","B05","Comilla",              "018"],
  ["Capital Drug House",         "CO4","B06","Panthapath, Dhaka",    "019"],
  ["Sunrise Wholesale Pharma",   "CO5","B07","Uttara, Dhaka",        "017"],
  ["Al-Amin Medical Traders",    "CO6","B08","Barishal Sadar",       "018"],
  ["Rahman Brothers Pharma",     "CO7","B01","Rayer Bazar, Dhaka",   "019"],
  ["Meghna Pharma Distributors", "CO8","B02","Faridpur",             "017"],
  ["Padma Medical Suppliers",    "CO1","B03","Jessore",              "018"],
  ["Continental Drug Agency",    "CO2","B04","Noakhali",             "019"],
  ["Star Pharma Traders",        "CO3","B05","Bogra",                "017"],
  ["Delta Medical Suppliers",    "CO4","B06","Mymensingh",           "018"],
];

export const SUPPLIERS = SUPPLIER_ROWS.map(([name,companyId,branchId,address,prefix],i) => ({
  id:            `SU${String(i+1).padStart(3,"0")}`,
  code:          `SUPP-${String(i+1).padStart(4,"0")}`,
  name,
  companyId,    // manufacturer company
  branchId,     // supplying branch
  orgId:         BRANCHES.find(b=>b.id===branchId)?.orgId || "ORG1",
  orgCompanyId:  BRANCHES.find(b=>b.id===branchId)?.companyId || "OC1",
  address,
  phone:         `${prefix}${int(rng,10000000,99999999)}`,
  email:         `${name.toLowerCase().replace(/[\s.]+/g,"").slice(0,15)}@supplier.com`,
  creditLimit:   round2(int(rng,500000,5000000)),
  openingBalance:round2(int(rng,0,200000)),
  status:        i<18?"Active":"Inactive",
  insertedDate:  fmtDate(addDays(TODAY,-int(rng,60,900))),
}));

/* ─── HR ─────────────────────────────────────────────────────────────────── */
export const DEPARTMENTS = [
  {id:"DEPT1",name:"Sales"},{id:"DEPT2",name:"Purchase"},{id:"DEPT3",name:"Accounts"},
  {id:"DEPT4",name:"Warehouse"},{id:"DEPT5",name:"Delivery"},{id:"DEPT6",name:"IT"},{id:"DEPT7",name:"Administration"},
];
export const DESIGNATIONS = [
  {id:"DES1",name:"Manager"},{id:"DES2",name:"Officer"},{id:"DES3",name:"Executive"},
  {id:"DES4",name:"Pharmacist"},{id:"DES5",name:"Delivery Man"},{id:"DES6",name:"Assistant"},{id:"DES7",name:"Accountant"},
];

const EMP_NAMES = [
  "Md Touhidur Rahman","Nusrat Jahan","Rezaul Karim","Farhana Akter","Shahriar Kabir",
  "Tania Sultana","Rafiq Islam","Kamal Hossain","Shafiq Ahmed","Milon Sarker",
  "Jahid Hasan","Rubina Khatun","Ashraful Islam","Nasrin Begum","Tanvir Hossain",
  "Sadia Rahman","Imran Khan","Roksana Akter","Arif Hossain","Shirin Akter",
];
export const EMPLOYEES = EMP_NAMES.map((name,i) => ({
  id:`EMP${String(i+1).padStart(3,"0")}`,name,
  deptId:DEPARTMENTS[i%DEPARTMENTS.length].id,
  designationId:DESIGNATIONS[i%DESIGNATIONS.length].id,
  branchId:BRANCHES[i%BRANCHES.length].id,
  orgId:BRANCHES[i%BRANCHES.length].orgId,
  phone:`017${int(rng,10000000,99999999)}`,
  email:`${name.toLowerCase().replace(/\s+/g,".")}@friendspharma.com`,
  joinDate:fmtDate(addDays(TODAY,-int(rng,30,1200))),
  status:i<18?"Active":"Inactive",
}));

export const DELIVERY_MEN = (() => {
  const base = [
    {id:"DM01",name:"Rafiq Islam",  branchId:"B01",vehicleNo:"DHA-1234",phone:"01712345601",status:"Active"},
    {id:"DM02",name:"Kamal Hossain",branchId:"B02",vehicleNo:"DHA-5678",phone:"01812345602",status:"Active"},
    {id:"DM03",name:"Shafiq Ahmed", branchId:"B03",vehicleNo:"CTG-2345",phone:"01912345603",status:"Active"},
    {id:"DM04",name:"Milon Sarker", branchId:"B04",vehicleNo:"CTG-6789",phone:"01712345604",status:"Active"},
    {id:"DM05",name:"Jahid Hasan",  branchId:"B05",vehicleNo:"KHU-3456",phone:"01812345605",status:"Active"},
    {id:"DM06",name:"Arif Hossain", branchId:"B06",vehicleNo:"RAJ-1122",phone:"01912345606",status:"Active"},
  ];
  return base;
})();


/* ─── BATCHES ────────────────────────────────────────────────────────────── */
export const BATCHES = [];
PRODUCTS.forEach(p => {
  const nBatch = int(rng,1,3);
  for(let b=0;b<nBatch;b++){
    const expDays = int(rng,-15,540);
    const qty     = int(rng,0,420);
    BATCHES.push({
      id:            `${p.id}-BT${b+1}`,
      productId:     p.id,
      batchNo:       `BN${p.id.slice(1).padStart(3,"0")}${String.fromCharCode(65+b)}${int(rng,100,999)}`,
      expiryDate:    fmtDate(addDays(TODAY,expDays)),
      purchasePrice: round2(p.tp*(1-0.05)),
      mrp:           p.mrp,
      qtyPcs:        qty,
      branchId:      pick(rng,BRANCHES).id,
      supplierId:    pick(rng,SUPPLIERS).id,
    });
  }
});

export function stockForProduct(productId, branchId){
  return BATCHES.filter(b=>b.productId===productId&&b.qtyPcs>0&&(!branchId||b.branchId===branchId))
    .reduce((s,b)=>s+b.qtyPcs,0);
}
export function batchesForProduct(productId, branchId){
  return BATCHES.filter(b=>b.productId===productId&&b.qtyPcs>0&&(!branchId||b.branchId===branchId))
    .sort((a,b)=>a.expiryDate.localeCompare(b.expiryDate));
}

/* ─── PURCHASES (200) ────────────────────────────────────────────────────── */
export const PURCHASES = [];
const PU_TYPES = ["Regular","Emergency","Seasonal"];
const PAY_TYPES = ["Cash","Credit","Bank Transfer","bKash","Nagad"];
for(let i=0;i<200;i++){
  const supplier = pick(rng,SUPPLIERS.filter(s=>s.status==="Active"));
  const branch   = pick(rng,BRANCHES);
  const daysAgo  = int(rng,0,90);
  const date     = fmtDate(addDays(TODAY,-daysAgo));
  const lineCount= int(rng,1,6);
  const lines    = pickN(rng,PRODUCTS.filter(p=>p.status==="Active"),lineCount).map(p=>{
    const boxQty   = int(rng,2,20);
    const pcs      = boxQty*p.pcsPerBox;
    const discPct  = pick(rng,[0,2,3,5]);
    const lineTotal= round2(pcs*p.tp*(1-discPct/100));
    return {
      productId:p.id, productName:p.name, genericId:p.genericId, subCategoryId:p.subCategoryId,
      boxQty, pcs, tp:p.tp, mrp:p.mrp, discPct, lineTotal,
      batchNo:`BN${p.id.slice(1).padStart(3,"0")}X${int(rng,100,999)}`,
      expiryDate:fmtDate(addDays(TODAY,int(rng,90,720))), vatPct:p.vatPct,
    };
  });
  const subtotal = round2(lines.reduce((s,l)=>s+l.lineTotal,0));
  const invDiscPct = pick(rng,[0,0,0,2,3]);
  const total = round2(subtotal*(1-invDiscPct/100));
  const given = pick(rng,[total,round2(total*0.6),round2(total*0.3),0]);
  PURCHASES.push({
    id:`PU-${String(i+1).padStart(6,"0")}`,type:"PU",
    supplierId:supplier.id, supplierName:supplier.name,
    branchId:branch.id, orgId:branch.orgId, companyId:branch.companyId,
    date, invoiceNo:`INV-${int(rng,10000,99999)}`, challanNo:`CHL-${int(rng,1000,9999)}`,
    purchaseType:pick(rng,PU_TYPES), paymentType:pick(rng,PAY_TYPES),
    lines, subtotal, invoiceDiscPct:invDiscPct, total, given,
    due:round2(total-given),
    status:pick(rng,["Completed","Completed","Completed","Pending","Cancelled"]),
    insertedBy:"USR001", insertedDate:date,
  });
}


/* ─── SALES (500) ────────────────────────────────────────────────────────── */
export const ORDER_STAGES = ["Submitted","Confirmed","Delivery Assigned","On The Way","Delivered","Collected"];
export const SALES = [];
for(let i=0;i<500;i++){
  const customer= pick(rng,CUSTOMERS.filter(c=>c.status==="Active"));
  const branch  = BRANCHES.find(b=>b.id===customer.branchId)||pick(rng,BRANCHES);
  const region  = REGIONS.find(r=>r.id===branch.regionId);
  const dm      = pick(rng,DELIVERY_MEN);
  const daysAgo = int(rng,0,60);
  const date    = fmtDate(addDays(TODAY,-daysAgo));
  const lineCount=int(rng,1,5);
  const lines   = pickN(rng,PRODUCTS.filter(p=>p.status==="Active"),lineCount).map(p=>{
    const unitMode= pick(rng,["PCS","STRIP","BOX"]);
    const qty     = int(rng,1,unitMode==="BOX"?4:unitMode==="STRIP"?6:30);
    const pcs     = unitMode==="BOX"?qty*p.pcsPerBox:unitMode==="STRIP"?qty*p.pcsPerLeaf:qty;
    const unitPrice= round2(p.mrp*(unitMode==="BOX"?p.pcsPerBox:unitMode==="STRIP"?p.pcsPerLeaf:1)*0.97);
    const discPct  = pick(rng,[0,0,5]);
    const lineTotal= round2(unitPrice*qty*(1-discPct/100));
    return {
      productId:p.id, productName:p.name, genericId:p.genericId, subCategoryId:p.subCategoryId,
      unitMode, qty, pcs, unitPrice, discPct, lineTotal,
      batchNo:`BN${p.id.slice(1).padStart(3,"0")}A${int(rng,100,999)}`,
      expiryDate:fmtDate(addDays(TODAY,int(rng,30,540))), vatPct:p.vatPct,
    };
  });
  const subtotal = round2(lines.reduce((s,l)=>s+l.lineTotal,0));
  const discPct  = pick(rng,[0,0,5,10]);
  const deliveryCharge= pick(rng,[0,0,60,80,100]);
  const grandTotal= round2(subtotal*(1-discPct/100)+deliveryCharge);
  const stageIdx = int(rng,0,5);
  const stage    = ORDER_STAGES[stageIdx];
  const paid     = stage==="Collected"?grandTotal:pick(rng,[0,round2(grandTotal*0.5),grandTotal]);
  SALES.push({
    id:`SL-${String(i+1).padStart(6,"0")}`, type:"SL",
    customerId:customer.id, customerName:customer.name,
    branchId:branch.id, orgId:branch.orgId, companyId:branch.companyId,
    regionId:region?.id||"R1", areaId:customer.areaId,
    date, invoiceNo:`SINV-${int(rng,10000,99999)}`,
    deliveryAddress:customer.address, paymentType:pick(rng,PAY_TYPES),
    deliveryCharge, lines, subtotal, discPct, grandTotal,
    paidAmount:paid, due:round2(grandTotal-paid),
    status:stageIdx>=4?"Completed":"Pending", stage,
    deliveryManId:stageIdx>=2?dm.id:null, deliveryManName:stageIdx>=2?dm.name:null,
    paymentStatus:paid>=grandTotal?"Paid":paid>0?"Partial":"Unpaid",
    merged:false, mergedInto:null,
    insertedBy:"USR001", insertedDate:date, remarks:"",
  });
}

export const SALES_RETURNS = SALES.slice(0,25).map((s,i)=>{
  const line=s.lines[0];
  const retQty=Math.max(1,Math.floor(line.qty/3));
  return {
    id:`SR-${String(i+1).padStart(6,"0")}`, type:"SR",
    originalSaleId:s.id, customerId:s.customerId, customerName:s.customerName,
    date:s.date, productId:line.productId, productName:line.productName,
    batchNo:line.batchNo, returnQty:retQty,
    returnAmount:round2(retQty*line.unitPrice),
    reason:pick(rng,["Damaged pack","Wrong item","Near expiry","Customer changed mind","Quality issue"]),
    status:"Approved", insertedBy:"USR001", insertedDate:s.date,
  };
});

export const PURCHASE_RETURNS = PURCHASES.slice(0,15).map((p,i)=>{
  const line=p.lines[0];
  const retQty=Math.max(1,Math.floor(line.pcs/4));
  return {
    id:`PR-${String(i+1).padStart(6,"0")}`, type:"PR",
    originalPurchaseId:p.id, supplierId:p.supplierId, supplierName:p.supplierName,
    date:p.date, productId:line.productId, productName:line.productName,
    batchNo:line.batchNo, returnPcs:retQty,
    returnAmount:round2(retQty*line.tp),
    reason:pick(rng,["Damaged on delivery","Wrong product","Near expiry on arrival","Price dispute"]),
    status:"Approved", insertedBy:"USR001", insertedDate:p.date,
  };
});


/* ─── DELIVERY & COLLECTION ──────────────────────────────────────────────── */
export const DELIVERY_ORDERS = SALES
  .filter(s=>["Delivery Assigned","On The Way","Delivered","Collected"].includes(s.stage))
  .slice(0,80)
  .map((s,i)=>({
    id:`DEL-${String(i+1).padStart(5,"0")}`,
    saleId:s.id, customerId:s.customerId, customerName:s.customerName,
    deliveryAddress:s.deliveryAddress, areaId:s.areaId,
    deliveryManId:s.deliveryManId, deliveryManName:s.deliveryManName,
    branchId:s.branchId,
    invoiceAmount:s.grandTotal, deliveryCharge:s.deliveryCharge,
    paidAmount:s.paidAmount, due:s.due,
    status:s.stage==="Delivery Assigned"?"Assigned":s.stage==="On The Way"?"On The Way":s.stage==="Delivered"?"Delivered":"Collected",
    assignedDate:s.date,
    deliveredDate:["Delivered","Collected"].includes(s.stage)?fmtDate(addDays(new Date(s.date),1)):null,
    collectedDate:s.stage==="Collected"?fmtDate(addDays(new Date(s.date),2)):null,
    collectedAmount:s.stage==="Collected"?s.grandTotal:0,
  }));

export const COLLECTIONS = DELIVERY_ORDERS
  .filter(d=>d.status==="Collected")
  .map((d,i)=>({
    id:`COL-${String(i+1).padStart(5,"0")}`,
    deliveryOrderId:d.id, saleId:d.saleId,
    deliveryManId:d.deliveryManId, deliveryManName:d.deliveryManName,
    customerId:d.customerId, customerName:d.customerName,
    invoiceAmount:d.invoiceAmount,
    collectedAmount:d.collectedAmount,
    outstanding:round2(d.invoiceAmount-d.collectedAmount),
    collectionDate:d.collectedDate||TODAY_STR,
    paymentType:pick(rng,["Cash","bKash","Nagad"]),
    voucherNo:`VCHR-${int(rng,10000,99999)}`,
    status:"Confirmed", insertedBy:"USR001",
  }));

/* ─── REQUISITIONS ───────────────────────────────────────────────────────── */
export const REQUISITIONS = [];
const lowProds = PRODUCTS.filter(p=>p.status==="Active"&&stockForProduct(p.id)<p.alertQty);
const reqChunks=[];
for(let i=0;i<Math.min(lowProds.length,48);i+=4) reqChunks.push(lowProds.slice(i,i+4));
const REQ_ST=["Draft","Submitted","Pending Approval","Approved","Rejected","Converted"];
reqChunks.forEach((chunk,idx)=>{
  const supplier=pick(rng,SUPPLIERS.filter(s=>s.status==="Active"));
  const branch=pick(rng,BRANCHES);
  const daysAgo=int(rng,0,20);
  REQUISITIONS.push({
    id:`RQ-${String(idx+1).padStart(5,"0")}`,
    supplierId:supplier.id, supplierName:supplier.name,
    branchId:branch.id, orgId:branch.orgId, companyId:branch.companyId,
    date:fmtDate(addDays(TODAY,-daysAgo)), requiredDate:fmtDate(addDays(TODAY,7)),
    purchaseType:pick(rng,["MNP","MRP"]), status:pick(rng,REQ_ST), remarks:"",
    lines:chunk.map(p=>({
      productId:p.id, productName:p.name, genericId:p.genericId,
      currentStock:stockForProduct(p.id), alertQty:p.alertQty,
      safetyStock:p.safetyStock, reorderLevel:p.reorderLevel,
      suggestedBoxQty:Math.max(2,Math.ceil((p.alertQty*1.8-stockForProduct(p.id))/p.pcsPerBox)),
      requiredQty:p.alertQty*2, supplierId:supplier.id, purchasePrice:p.tp,
      estimatedAmount:round2(Math.max(2,Math.ceil((p.alertQty*1.8-stockForProduct(p.id))/p.pcsPerBox))*p.pcsPerBox*p.tp),
    })),
    insertedBy:"USR001", insertedDate:fmtDate(addDays(TODAY,-daysAgo)),
    approvedBy:null, approvedDate:null, convertedPurchaseId:null,
  });
});


/* ─── SALES PERFORMANCE (Sales% — salesman targets) ─────────────────────── */
export const SALESPERSONS = [
  {id:"SP01",name:"Md Touhidur Rahman", branchId:"B01", regionId:"R1"},
  {id:"SP02",name:"Nusrat Jahan",       branchId:"B01", regionId:"R1"},
  {id:"SP03",name:"Rezaul Karim",       branchId:"B02", regionId:"R1"},
  {id:"SP04",name:"Farhana Akter",      branchId:"B03", regionId:"R2"},
  {id:"SP05",name:"Shahriar Kabir",     branchId:"B04", regionId:"R2"},
  {id:"SP06",name:"Ashraful Islam",     branchId:"B05", regionId:"R3"},
  {id:"SP07",name:"Tanvir Hossain",     branchId:"B06", regionId:"R4"},
  {id:"SP08",name:"Imran Khan",         branchId:"B07", regionId:"R5"},
];

// Monthly targets & actuals for last 6 months
export const SALES_PERFORMANCE = SALESPERSONS.map(sp=>{
  const months = ["2026-03","2026-04","2026-05","2026-06","2026-07","2026-08"];
  const target = int(rng,400000,900000);
  return {
    ...sp,
    target,
    actual:round2(target*(0.7+rng()*0.5)),   // 70%–120% of target
    commission:0.025,                          // 2.5%
    monthlyData: months.map(m=>({
      month:m,
      target:round2(target/6),
      actual:round2((target/6)*(0.65+rng()*0.7)),
      orders:int(rng,20,80),
      customers:int(rng,8,25),
    })),
    topProducts: pickN(rng,PRODUCTS,3).map(p=>({id:p.id,name:p.name,units:int(rng,50,300),revenue:round2(int(rng,50,300)*p.mrp)})),
  };
});

/* ─── RBAC ───────────────────────────────────────────────────────────────── */
export const MODULES_LIST = [
  {id:"MOD01",name:"Dashboard",          routeKey:"dashboard",        icon:"dashboard",  group:"OVERVIEW"},
  {id:"MOD02",name:"Master Data",        routeKey:"master",           icon:"master",     group:"OPERATIONS"},
  {id:"MOD03",name:"Purchase",           routeKey:"purchase",         icon:"purchase",   group:"OPERATIONS"},
  {id:"MOD04",name:"Sales",              routeKey:"sales",            icon:"sales",      group:"OPERATIONS"},
  {id:"MOD05",name:"Quick Sales / POS",  routeKey:"quicksales",       icon:"quicksales", group:"OPERATIONS"},
  {id:"MOD06",name:"Sales Activity",     routeKey:"activity",         icon:"activity",   group:"OPERATIONS"},
  {id:"MOD07",name:"Sales Performance",  routeKey:"salesperformance", icon:"salesperformance", group:"OPERATIONS"},
  {id:"MOD08",name:"Sales Discount",     routeKey:"salesdiscount",    icon:"salesdiscount",group:"OPERATIONS"},
  {id:"MOD18",name:"Sales Return",       routeKey:"salesreturn",      icon:"salesreturn",group:"OPERATIONS"},
  {id:"MOD09",name:"Delivery",           routeKey:"delivery",         icon:"delivery",   group:"OPERATIONS"},
  {id:"MOD10",name:"Collection",         routeKey:"collection",       icon:"collection", group:"OPERATIONS"},
  {id:"MOD11",name:"Requisition",        routeKey:"requisition",      icon:"requisition",group:"OPERATIONS"},
  {id:"MOD12",name:"Invoice Merge",      routeKey:"merge",            icon:"merge",      group:"OPERATIONS"},
  {id:"MOD13",name:"Inventory",          routeKey:"inventory",        icon:"inventory",  group:"REPORTS"},
  {id:"MOD14",name:"Reports",            routeKey:"reports",          icon:"reports",    group:"REPORTS"},
  {id:"MOD15",name:"AI Copilot",         routeKey:"ai",               icon:"ai",         group:"REPORTS"},
  {id:"MOD16",name:"RBAC / Admin",       routeKey:"rbac",             icon:"rbac",       group:"ADMIN"},
];
export const MENUS = MODULES_LIST.map((m,i)=>({id:`MNU${String(i+1).padStart(3,"0")}`,name:m.name,moduleId:m.id,sortOrder:i+1}));
export const ROLES=[
  {id:"ROLE01",name:"Super Admin",       description:"Full system access"},
  {id:"ROLE02",name:"Branch Manager",    description:"Branch-level operations"},
  {id:"ROLE03",name:"Pharmacist",        description:"Sales and inventory"},
  {id:"ROLE04",name:"Accounts Officer",  description:"Financial reports"},
  {id:"ROLE05",name:"Purchase Officer",  description:"Purchase & requisition"},
  {id:"ROLE06",name:"Delivery Coordinator",description:"Delivery & collection"},
  {id:"ROLE07",name:"Store Officer",     description:"Inventory management"},
  {id:"ROLE08",name:"Viewer",            description:"Read-only access"},
  {id:"ROLE09",name:"Sales Manager",     description:"Sales and reports"},
];
const PERM_KEYS=["view","create","edit","delete","approve","print","export","finalize","cancel"];
const rng2=makeRng(9999);
export const PERMISSIONS={};
ROLES.forEach(r=>{
  PERMISSIONS[r.id]={};
  MENUS.forEach(m=>{
    const full=r.id==="ROLE01";
    PERMISSIONS[r.id][m.id]=Object.fromEntries(PERM_KEYS.map(k=>[k,full?true:rng2()>(k==="view"?0.15:0.55)]));
  });
});
export const USERS=[
  {id:"USR001",username:"admin",   password:"admin123",  name:"Md Touhidur Rahman",roleId:"ROLE01",branchId:"B01",email:"admin@friendspharma.com",  mobile:"01712345678",status:"Active",  lastLogin:TODAY_STR,avatarUrl:null},
  {id:"USR002",username:"nusrat",  password:"nusrat123", name:"Nusrat Jahan",      roleId:"ROLE02",branchId:"B01",email:"nusrat@friendspharma.com", mobile:"01812345679",status:"Active",  lastLogin:TODAY_STR,avatarUrl:null},
  {id:"USR003",username:"rezaul",  password:"rezaul123", name:"Rezaul Karim",      roleId:"ROLE03",branchId:"B02",email:"rezaul@friendspharma.com", mobile:"01912345680",status:"Active",  lastLogin:fmtDate(addDays(TODAY,-1)),avatarUrl:null},
  {id:"USR004",username:"farhana", password:"farhana123",name:"Farhana Akter",     roleId:"ROLE04",branchId:"B03",email:"farhana@friendspharma.com",mobile:"01712345681",status:"Active",  lastLogin:fmtDate(addDays(TODAY,-2)),avatarUrl:null},
  {id:"USR005",username:"shahriar",password:"shahriar123",name:"Shahriar Kabir",   roleId:"ROLE05",branchId:"B04",email:"shahriar@friendspharma.com",mobile:"01812345682",status:"Active", lastLogin:fmtDate(addDays(TODAY,-1)),avatarUrl:null},
  {id:"USR006",username:"tania",   password:"tania123",  name:"Tania Sultana",     roleId:"ROLE06",branchId:"B05",email:"tania@friendspharma.com",  mobile:"01912345683",status:"Inactive",lastLogin:fmtDate(addDays(TODAY,-30)),avatarUrl:null},
  {id:"USR007",username:"rafiq",   password:"rafiq123",  name:"Rafiq Islam",       roleId:"ROLE06",branchId:"B01",email:"rafiq@friendspharma.com",  mobile:"01712345684",status:"Active",  lastLogin:TODAY_STR,avatarUrl:null},
  {id:"USR008",username:"kamal",   password:"kamal123",  name:"Kamal Hossain",     roleId:"ROLE07",branchId:"B02",email:"kamal@friendspharma.com",  mobile:"01812345685",status:"Active",  lastLogin:TODAY_STR,avatarUrl:null},
  {id:"USR009",username:"shafiq",  password:"shafiq123", name:"Shafiq Ahmed",      roleId:"ROLE09",branchId:"B03",email:"shafiq@friendspharma.com", mobile:"01912345686",status:"Active",  lastLogin:fmtDate(addDays(TODAY,-3)),avatarUrl:null},
  {id:"USR010",username:"viewer",  password:"viewer123", name:"Read-Only User",    roleId:"ROLE08",branchId:"B01",email:"viewer@friendspharma.com", mobile:"01712345687",status:"Active",  lastLogin:fmtDate(addDays(TODAY,-5)),avatarUrl:null},
];


/* ─── LOOKUP MAPS ─────────────────────────────────────────────────────────── */
export const byId=(arr)=>Object.fromEntries((arr||[]).map(x=>[x.id,x]));
export const PRODUCT_BY_ID    = byId(PRODUCTS);
export const CUSTOMER_BY_ID   = byId(CUSTOMERS);
export const SUPPLIER_BY_ID   = byId(SUPPLIERS);
export const BRANCH_BY_ID     = byId(BRANCHES);
export const REGION_BY_ID     = byId(REGIONS);
export const CATEGORY_BY_ID   = byId(CATEGORIES);
export const SUBCATEGORY_BY_ID= byId(SUBCATEGORIES);
export const COMPANY_BY_ID    = byId(COMPANIES);
export const GENERIC_BY_ID    = byId(GENERICS);
export const EMPLOYEE_BY_ID   = byId(EMPLOYEES);
export const AREA_BY_ID       = byId(AREAS);
export const USER_BY_ID       = byId(USERS);
export const BOX_BY_ID        = byId(BOXES);
export const LEAF_BY_ID       = byId(LEAFS);
export const ORG_COMPANY_BY_ID= byId(ORG_COMPANIES);
export const ORGANIZATION_BY_ID=byId(ORGANIZATIONS);
export const DELIVERY_MAN_BY_ID=byId(DELIVERY_MEN);

/** Legacy compat */
export const MODULES = MODULES_LIST.map(m=>m.name);
