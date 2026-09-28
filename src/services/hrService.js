/**
 * hrService.js — every HR call the screens make.
 *
 * One file for five screens, because they share a vocabulary and half their
 * lookups: the payroll screen needs leave types to explain an unpaid day, the
 * employees screen needs salary structures, and the attendance screen needs the
 * same status labels the payslip prints.
 *
 * <b>Nothing here sends a branch.</b> The server resolves it from the session,
 * and a branch in a request body is a branch a client can change.
 *
 * <b>Nothing here recomputes money.</b> Unlike salesPercentService, which
 * previews a price while the user types, every figure on a payslip comes from
 * the server. Two implementations of payroll arithmetic is one more than the
 * number that can be right, and a preview that disagrees with the payslip is
 * worse than no preview.
 */
import apiClient from '../lib/apiClient.js';

/**
 * The payload out of the ApiResponse envelope.
 *
 * <p><b>A deliberately absent `data` means null, not the envelope.</b>
 * `ApiResponse` is annotated `@JsonInclude(NON_NULL)`, so an endpoint answering
 * with no payload — "this branch has no working policy" — sends
 * `{success, message, timestamp}` and no `data` key at all. A plain
 * `r.data !== undefined ? r.data : r` then hands back the envelope, which is
 * truthy, and the screen concludes the thing exists. That is how the policy card
 * came to show a policy made entirely of dashes.
 */
const unwrap = (r) => {
  if (!r || typeof r !== 'object') return r;
  if ('data' in r) return r.data;
  // An envelope with no data: the server said "nothing", so say nothing.
  if ('success' in r && 'timestamp' in r) return null;
  return r;
};

/* ── The page the server sends back, flattened the way the grids want it ── */
const unwrapPage = (r) => {
  const d = unwrap(r) || {};
  return {
    items: d.content || d.items || (Array.isArray(d) ? d : []),
    page: d.page ?? d.number ?? 0,
    size: d.size ?? 20,
    total: d.totalElements ?? d.total ?? 0,
    totalPages: d.totalPages ?? 1,
  };
};

/* ═══════════════════════════════════════════════════════════════════════════
   Summary, profile, vocabulary
   ═══════════════════════════════════════════════════════════════════════════ */

export const getSummary  = () => apiClient.get('/api/v1/hr/summary').then(unwrap);
export const getProfile  = (id) => apiClient.get(`/api/v1/hr/employees/${id}/profile`).then(unwrap);
export const setPayBasis = (id, body) => apiClient.put(`/api/v1/hr/employees/${id}/pay-basis`, body).then(unwrap);

/**
 * The part of a staff record HR owns — personal detail and employment terms.
 *
 * <p>Name, code, phone, role, department and designation are deliberately not
 * here: they stay on `/api/employees`, which the Master Data screen and the
 * delivery list have used since before HR existed. Every field is optional, so
 * each section of the form saves on its own.
 */
export const updateEmployeeDetails = (id, body) =>
  apiClient.put(`/api/v1/hr/employees/${id}/details`, body).then(unwrap);

export const MARITAL_STATUSES = ['SINGLE', 'MARRIED', 'DIVORCED', 'WIDOWED', 'OTHER'];
export const BLOOD_GROUPS = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];
export const GENDERS = ['MALE', 'FEMALE', 'OTHER'];

/* ── Attendance correction ───────────────────────────────────────────────
   A recorded day is disputed rather than overwritten: the row keeps saying
   what it says and the proposal sits beside it until somebody with Approve
   decides. It is also the only route by which a device punch can be changed. */

export const getCorrections     = () => apiClient.get('/api/v1/hr/attendance/corrections').then(unwrap);
export const requestCorrection  = (id, body) => apiClient.post(`/api/v1/hr/attendance/${id}/correction`, body).then(unwrap);
export const approveCorrection  = (id, remarks) => apiClient.post(`/api/v1/hr/attendance/${id}/correction/approve`, { remarks }).then(unwrap);
export const rejectCorrection   = (id, remarks) => apiClient.post(`/api/v1/hr/attendance/${id}/correction/reject`, { remarks }).then(unwrap);

/**
 * Replaces an employee's photograph.
 *
 * <p>Multipart, through {@code apiClient.upload}, which deliberately does not
 * set a Content-Type — the browser has to add the multipart boundary itself,
 * and setting the header by hand strips it and the request arrives unparseable.
 *
 * <p>The file is validated on the server: extension whitelist, the declared MIME
 * type having to agree with it, real image magic bytes and a size limit. There
 * is no point re-implementing any of that here — a client-side check is a
 * courtesy, not a control — but the accept attribute on the input keeps the
 * common mistake out of the round trip.
 */
export const uploadEmployeeImage = (employeeId, file) => {
  const form = new FormData();
  form.append('file', file);
  return apiClient.upload(`/api/v1/hr/employees/${employeeId}/image`, form).then(unwrap);
};

export const removeEmployeeImage = (employeeId) =>
  apiClient.delete(`/api/v1/hr/employees/${employeeId}/image`).then(unwrap);

/** What the file input should accept. Mirrors the server's whitelist. */
export const IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp';

/**
 * The value sets the pickers use.
 *
 * <p>Fetched rather than hard-coded, so adding an employment type or a
 * separation reason on the server reaches the UI without a frontend change.
 */
export const getVocabulary = () => apiClient.get('/api/v1/hr/vocabulary').then(unwrap);

/** The staff list. Reuses the existing master endpoint — HR does not have its own. */
export const getEmployees = (params = {}) =>
  apiClient.get('/api/employees', { page: 0, size: 200, ...params }).then(unwrapPage);

/* ═══════════════════════════════════════════════════════════════════════════
   Attendance
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * One page of the register for one day, with the whole day's counts.
 *
 * <p>Lists people, not rows — the ones with no row yet are the point. Paged
 * and filtered on the server, because a branch of 2,500 is not a screen and a
 * 2,500-row response on every date change is not a register anybody can use.
 * The counts (`total`, `recorded`, `notRecorded`, `byStatus`) are the branch's
 * for the day, never the page's.
 *
 * @param params { q, departmentId, shiftId, filter, page, size }
 *   filter  '' for everyone, NOT_RECORDED for people with no row, or a status
 */
export const getAttendanceDay = (date, params = {}) => {
  const p = { ...(date ? { date } : {}), page: 0, size: 50, ...params };
  // Blank filters are not sent at all: `q=` and `departmentId=` reach the
  // server as empty strings, and an empty string is not a Long.
  Object.keys(p).forEach((k) => { if (p[k] === '' || p[k] == null) delete p[k]; });
  return apiClient.get('/api/v1/hr/attendance/day', p).then(unwrap);
};

/**
 * Mark everyone the filter matches who has no row yet.
 *
 * <p>How a large register is finished: the exceptions are marked by hand,
 * then "everyone else was present" is one request. Only people with no row
 * are touched. Done on the server rather than by posting rows from here,
 * because the alternative is a browser downloading 2,500 people to send
 * 2,400 of them back.
 */
export const fillAttendanceDay = ({ attendanceDate, attendanceStatus, q, departmentId, shiftId }) =>
  apiClient.post('/api/v1/hr/attendance/day/fill', {
    attendanceDate, attendanceStatus,
    q: q || null,
    departmentId: departmentId || null,
    shiftId: shiftId || null,
  }).then(unwrap);

/**
 * A few people whose name or code contains what was typed.
 *
 * <p>For a picker, not a list: the same master endpoint the staff list reads,
 * asked for a page of twenty. Loading every employee into a `<select>` so the
 * browser can filter it is the thing this replaces.
 */
export const searchEmployees = (q, size = 20) =>
  apiClient.get('/api/employees', { q: q || undefined, page: 0, size }).then(unwrapPage);

/** The org's departments, for the register's filter. */
export const getDepartments = () =>
  apiClient.get('/api/departments').then(unwrap);

export const getAttendanceFor = (employeeId, from, to) =>
  apiClient.get(`/api/v1/hr/attendance/employee/${employeeId}`, { from, to }).then(unwrap);

export const recordAttendance = (body) =>
  apiClient.post('/api/v1/hr/attendance', body).then(unwrap);

/** A whole day in one save — all or nothing on the server. */
export const recordAttendanceDay = (attendanceDate, rows) =>
  apiClient.post('/api/v1/hr/attendance/day', { attendanceDate, rows }).then(unwrap);

export const updateAttendance = (id, body) =>
  apiClient.put(`/api/v1/hr/attendance/${id}`, body).then(unwrap);

export const withdrawAttendance = (id) =>
  apiClient.delete(`/api/v1/hr/attendance/${id}`).then(unwrap);

/* ═══════════════════════════════════════════════════════════════════════════
   Working policy, shifts and holidays

   These live under attendance because the register is what they govern. A
   `configured: false` calendar means the branch has set no policy, and every
   day is then a working day — exactly as the application behaved before the
   policy table existed. The screen says that rather than implying somebody
   chose a seven-day week.
   ═══════════════════════════════════════════════════════════════════════════ */

export const getWorkingCalendar = (from, to) =>
  apiClient.get('/api/v1/hr/attendance/calendar', { from, to }).then(unwrap);

export const getShifts    = (activeOnly = true) =>
  apiClient.get('/api/v1/hr/attendance/shifts', { activeOnly }).then(unwrap);
export const createShift  = (body) => apiClient.post('/api/v1/hr/attendance/shifts', body).then(unwrap);
export const updateShift  = (id, body) => apiClient.put(`/api/v1/hr/attendance/shifts/${id}`, body).then(unwrap);
export const retireShift  = (id) => apiClient.delete(`/api/v1/hr/attendance/shifts/${id}`).then(unwrap);

export const getPolicy        = () => apiClient.get('/api/v1/hr/attendance/policy').then(unwrap);
export const getPolicyHistory = () => apiClient.get('/api/v1/hr/attendance/policy/history').then(unwrap);
export const setPolicy        = (body) => apiClient.post('/api/v1/hr/attendance/policy', body).then(unwrap);

export const getHolidays   = (year) =>
  apiClient.get('/api/v1/hr/attendance/holidays', year ? { year } : null).then(unwrap);
export const createHoliday = (body) => apiClient.post('/api/v1/hr/attendance/holidays', body).then(unwrap);
export const updateHoliday = (id, body) => apiClient.put(`/api/v1/hr/attendance/holidays/${id}`, body).then(unwrap);
export const removeHoliday = (id) => apiClient.delete(`/api/v1/hr/attendance/holidays/${id}`).then(unwrap);

/** Monday first, matching the ISO numbering the server stores. */
export const WEEK_DAYS = [
  { value: 1, label: 'Monday' },    { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' }, { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },    { value: 6, label: 'Saturday' },
  { value: 7, label: 'Sunday' },
];

export const HOLIDAY_TYPES = ['GOVERNMENT', 'PUBLIC', 'COMPANY', 'SPECIAL', 'RELIGIOUS'];

/** "HH:mm" from whatever shape the server sent — a string or [h, m]. */
export const clock = (t) => {
  if (!t) return '—';
  if (Array.isArray(t)) return `${String(t[0]).padStart(2, '0')}:${String(t[1] ?? 0).padStart(2, '0')}`;
  return String(t).slice(0, 5);
};

/* ═══════════════════════════════════════════════════════════════════════════
   Leave
   ═══════════════════════════════════════════════════════════════════════════ */

export const getLeaveTypes = (activeOnly = true) =>
  apiClient.get('/api/v1/hr/leave/types', { activeOnly }).then(unwrap);

export const createLeaveType = (body) => apiClient.post('/api/v1/hr/leave/types', body).then(unwrap);
export const updateLeaveType = (id, body) => apiClient.put(`/api/v1/hr/leave/types/${id}`, body).then(unwrap);
export const retireLeaveType = (id) => apiClient.delete(`/api/v1/hr/leave/types/${id}`).then(unwrap);

export const getLeaveBalances = (employeeId, year) =>
  apiClient.get(`/api/v1/hr/leave/balances/${employeeId}`, year ? { year } : null).then(unwrap);

export const openLeaveBalance = (body) =>
  apiClient.post('/api/v1/hr/leave/balances', body).then(unwrap);

export const searchLeave = (params = {}) =>
  apiClient.get('/api/v1/hr/leave', { page: 0, size: 20, ...params }).then(unwrapPage);

export const getPendingLeave = () => apiClient.get('/api/v1/hr/leave/pending').then(unwrap);

export const fileLeave    = (body) => apiClient.post('/api/v1/hr/leave', body).then(unwrap);
export const updateLeave  = (id, body) => apiClient.put(`/api/v1/hr/leave/${id}`, body).then(unwrap);
export const submitLeave  = (id) => apiClient.post(`/api/v1/hr/leave/${id}/submit`).then(unwrap);
export const approveLeave = (id, remarks) => apiClient.post(`/api/v1/hr/leave/${id}/approve`, { remarks }).then(unwrap);
export const rejectLeave  = (id, remarks) => apiClient.post(`/api/v1/hr/leave/${id}/reject`, { remarks }).then(unwrap);
export const cancelLeave  = (id, remarks) => apiClient.post(`/api/v1/hr/leave/${id}/cancel`, { remarks }).then(unwrap);

/* ═══════════════════════════════════════════════════════════════════════════
   Advance and loan
   ═══════════════════════════════════════════════════════════════════════════ */

export const searchAdvances = (params = {}) =>
  apiClient.get('/api/v1/hr/advances', { page: 0, size: 20, ...params }).then(unwrapPage);

export const createAdvance  = (body) => apiClient.post('/api/v1/hr/advances', body).then(unwrap);
export const submitAdvance  = (id) => apiClient.post(`/api/v1/hr/advances/${id}/submit`).then(unwrap);
export const approveAdvance = (id, body) => apiClient.post(`/api/v1/hr/advances/${id}/approve`, body).then(unwrap);
export const rejectAdvance  = (id, remarks) => apiClient.post(`/api/v1/hr/advances/${id}/reject`, { remarks }).then(unwrap);
export const payAdvance     = (id, body) => apiClient.post(`/api/v1/hr/advances/${id}/pay`, body).then(unwrap);
export const cancelAdvance  = (id, remarks) => apiClient.post(`/api/v1/hr/advances/${id}/cancel`, { remarks }).then(unwrap);

export const searchLoans   = (params = {}) =>
  apiClient.get('/api/v1/hr/loans', { page: 0, size: 20, ...params }).then(unwrapPage);

export const getLoan       = (id) => apiClient.get(`/api/v1/hr/loans/${id}`).then(unwrap);
export const createLoan    = (body) => apiClient.post('/api/v1/hr/loans', body).then(unwrap);
export const submitLoan    = (id) => apiClient.post(`/api/v1/hr/loans/${id}/submit`).then(unwrap);
export const approveLoan   = (id, body) => apiClient.post(`/api/v1/hr/loans/${id}/approve`, body).then(unwrap);
export const rejectLoan    = (id, remarks) => apiClient.post(`/api/v1/hr/loans/${id}/reject`, { remarks }).then(unwrap);
export const disburseLoan  = (id, body) => apiClient.post(`/api/v1/hr/loans/${id}/disburse`, body).then(unwrap);
export const cancelLoan    = (id, remarks) => apiClient.post(`/api/v1/hr/loans/${id}/cancel`, { remarks }).then(unwrap);

/* ═══════════════════════════════════════════════════════════════════════════
   Payroll
   ═══════════════════════════════════════════════════════════════════════════ */

export const getStructures   = (activeOnly = true) =>
  apiClient.get('/api/v1/hr/payroll/structures', { activeOnly }).then(unwrap);

export const getStructure    = (id) => apiClient.get(`/api/v1/hr/payroll/structures/${id}`).then(unwrap);
export const createStructure = (body) => apiClient.post('/api/v1/hr/payroll/structures', body).then(unwrap);
export const updateStructure = (id, body) => apiClient.put(`/api/v1/hr/payroll/structures/${id}`, body).then(unwrap);
export const retireStructure = (id) => apiClient.delete(`/api/v1/hr/payroll/structures/${id}`).then(unwrap);

export const getPeriods   = (year) =>
  apiClient.get('/api/v1/hr/payroll/periods', year ? { year } : null).then(unwrap);

export const createPeriod = (body) => apiClient.post('/api/v1/hr/payroll/periods', body).then(unwrap);
export const closePeriod  = (id) => apiClient.post(`/api/v1/hr/payroll/periods/${id}/close`).then(unwrap);

export const searchPayrolls = (params = {}) =>
  apiClient.get('/api/v1/hr/payroll', { page: 0, size: 20, ...params }).then(unwrapPage);

export const getPayroll = (id) => apiClient.get(`/api/v1/hr/payroll/${id}`).then(unwrap);
/** One payslip assembled for printing — letterhead, lines, payments, net in words. */
export const getPayslipDocument = (payrollId, lineId) =>
  apiClient.get(`/api/v1/hr/payroll/${payrollId}/lines/${lineId}/document`).then(unwrap);

export const calculatePayroll = (body) => apiClient.post('/api/v1/hr/payroll/calculate', body).then(unwrap);
export const reviewPayroll  = (id, remarks) => apiClient.post(`/api/v1/hr/payroll/${id}/review`, { remarks }).then(unwrap);
export const approvePayroll = (id, remarks) => apiClient.post(`/api/v1/hr/payroll/${id}/approve`, { remarks }).then(unwrap);
export const postPayroll    = (id, remarks) => apiClient.post(`/api/v1/hr/payroll/${id}/post`, { remarks }).then(unwrap);
export const cancelPayroll  = (id, remarks) => apiClient.post(`/api/v1/hr/payroll/${id}/cancel`, { remarks }).then(unwrap);

export const getPayments  = (id) => apiClient.get(`/api/v1/hr/payroll/${id}/payments`).then(unwrap);
export const paySalary    = (body) => apiClient.post('/api/v1/hr/payroll/pay', body).then(unwrap);
export const cancelPayment = (paymentId, remarks) =>
  apiClient.post(`/api/v1/hr/payroll/payments/${paymentId}/cancel`, { remarks }).then(unwrap);

/* ═══════════════════════════════════════════════════════════════════════════
   Separation
   ═══════════════════════════════════════════════════════════════════════════ */

export const searchSeparations = (params = {}) =>
  apiClient.get('/api/v1/hr/separations', { page: 0, size: 20, ...params }).then(unwrapPage);

export const fileSeparation    = (body) => apiClient.post('/api/v1/hr/separations', body).then(unwrap);
export const approveSeparation = (id, remarks) => apiClient.post(`/api/v1/hr/separations/${id}/approve`, { remarks }).then(unwrap);
export const rejectSeparation  = (id, remarks) => apiClient.post(`/api/v1/hr/separations/${id}/reject`, { remarks }).then(unwrap);
export const cancelSeparation  = (id, remarks) => apiClient.post(`/api/v1/hr/separations/${id}/cancel`, { remarks }).then(unwrap);
export const settleSeparation  = (id, body) => apiClient.post(`/api/v1/hr/separations/${id}/settle`, body).then(unwrap);

/* ═══════════════════════════════════════════════════════════════════════════
   Formatting the screens share
   ═══════════════════════════════════════════════════════════════════════════ */

export const money = (v) =>
  v == null || v === ''
    ? '—'
    : `৳${Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export const days = (v) =>
  v == null || v === '' ? '—' : String(Number(v)).replace(/\.00$/, '');

/** Minutes to "1h 30m". The server also sends hours; this is for a bare count. */
export const hoursOf = (minutes) => {
  if (!minutes) return '—';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h ? (m ? `${h}h ${m}m` : `${h}h`) : `${m}m`;
};

/**
 * The colour a status carries.
 *
 * <p>Kept beside the calls rather than in each screen, so a leave request and
 * a payroll run that are both "approved" look the same wherever they appear.
 */
export const toneFor = (status) => {
  switch (status) {
    case 'APPROVED': case 'PAID': case 'RECOVERED': case 'PRESENT': case 'SETTLED': case 'COMPLETED': case 'APPLIED':
    case 'BALANCED': case 'ACTIVE': case 'MATCHED': case 'DONE':          // Accounts
      return 'sage';
    case 'POSTED': case 'DISBURSED': case 'REVIEWED': case 'CALCULATED': case 'RECOVERING': case 'PROCESSING':
    case 'OPEN': case 'RUNNING':                                           // Accounts
      return 'teal';
    case 'SUBMITTED': case 'PENDING': case 'PARTIAL': case 'LATE': case 'EARLY_LEAVE': case 'HALF_DAY': case 'REQUESTED':
    case 'REOPENED': case 'OVER': case 'SKIPPED': case 'AMOUNT_DIFFERS':   // Accounts
      return 'amber';
    case 'REJECTED': case 'CANCELLED': case 'ABSENT': case 'CLOSED': case 'SUSPENDED':
    case 'LOCKED': case 'REVERSED': case 'SHORT': case 'FAILED': case 'MISSING_JOURNALS': case 'EXITED': case 'INACTIVE':   // Accounts
      return 'clay';
    default:
      return 'grey';
  }
};

/** Today, as the yyyy-MM-dd every date input and every endpoint expects. */
export const isoToday = () => new Date().toISOString().slice(0, 10);

/** The first day of the month a date falls in. */
export const monthStart = (iso) => (iso || isoToday()).slice(0, 8) + '01';

/* ═══════════════════════════════════════════════════════════════════════════
   Overtime claims

   Approving one writes its minutes onto the attendance row for that day, which
   is the figure payroll values. The claim never reaches a payslip itself — that
   is what stops an approved hour being paid twice.
   ═══════════════════════════════════════════════════════════════════════════ */

export const searchOvertime = (params = {}) =>
  apiClient.get('/api/v1/hr/overtime', { page: 0, size: 20, ...params }).then(unwrapPage);

export const getPendingOvertime = () => apiClient.get('/api/v1/hr/overtime/pending').then(unwrap);

export const claimOvertime   = (body) => apiClient.post('/api/v1/hr/overtime', body).then(unwrap);
export const submitOvertime  = (id) => apiClient.post(`/api/v1/hr/overtime/${id}/submit`).then(unwrap);
export const approveOvertime = (id, body) => apiClient.post(`/api/v1/hr/overtime/${id}/approve`, body).then(unwrap);
export const rejectOvertime  = (id, remarks) => apiClient.post(`/api/v1/hr/overtime/${id}/reject`, { remarks }).then(unwrap);
export const cancelOvertime  = (id, remarks) => apiClient.post(`/api/v1/hr/overtime/${id}/cancel`, { remarks }).then(unwrap);

/* ═══════════════════════════════════════════════════════════════════════════
   Increments

   The money is frozen at approval. Applying writes the frozen figure onto the
   employee, and is refused if the salary moved in between.
   ═══════════════════════════════════════════════════════════════════════════ */

export const searchIncrements = (params = {}) =>
  apiClient.get('/api/v1/hr/increments', { page: 0, size: 20, ...params }).then(unwrapPage);

export const getPendingIncrements = () => apiClient.get('/api/v1/hr/increments/pending').then(unwrap);
export const getDueIncrements     = () => apiClient.get('/api/v1/hr/increments/due').then(unwrap);
export const getSalaryHistory     = (employeeId) =>
  apiClient.get(`/api/v1/hr/increments/employee/${employeeId}`).then(unwrap);

export const proposeIncrement = (body) => apiClient.post('/api/v1/hr/increments', body).then(unwrap);
export const updateIncrement  = (id, body) => apiClient.put(`/api/v1/hr/increments/${id}`, body).then(unwrap);
export const submitIncrement  = (id) => apiClient.post(`/api/v1/hr/increments/${id}/submit`).then(unwrap);
export const approveIncrement = (id, body) => apiClient.post(`/api/v1/hr/increments/${id}/approve`, body).then(unwrap);
export const rejectIncrement  = (id, remarks) => apiClient.post(`/api/v1/hr/increments/${id}/reject`, { remarks }).then(unwrap);
export const applyIncrement   = (id) => apiClient.post(`/api/v1/hr/increments/${id}/apply`).then(unwrap);
export const cancelIncrement  = (id, remarks) => apiClient.post(`/api/v1/hr/increments/${id}/cancel`, { remarks }).then(unwrap);

export const INCREMENT_TYPES = ['ANNUAL', 'PROMOTION', 'PERFORMANCE', 'ADJUSTMENT'];

/** Minutes as the screen says them. The table holds minutes; nobody reads "510". */
export const asHours = (minutes) => {
  if (minutes == null) return '—';
  const m = Math.max(0, Number(minutes) || 0);
  const h = Math.floor(m / 60);
  const r = m % 60;
  if (!h) return `${r}m`;
  return r ? `${h}h ${r}m` : `${h}h`;
};

/* ═══════════════════════════════════════════════════════════════════════════
   Provident fund

   A branch ships with NO scheme, and with none payroll deducts no PF from
   anybody. A rate left blank means nobody has decided — not nought per cent —
   which is why the scheme carries rateSet and deducting as separate flags.
   ═══════════════════════════════════════════════════════════════════════════ */

export const getPfScheme        = () => apiClient.get('/api/v1/hr/pf/scheme').then(unwrap);
export const getPfSchemeHistory = () => apiClient.get('/api/v1/hr/pf/scheme/history').then(unwrap);
export const setPfScheme        = (body) => apiClient.post('/api/v1/hr/pf/scheme', body).then(unwrap);

export const getPfLedger    = (params = {}) =>
  apiClient.get('/api/v1/hr/pf/ledger', { page: 0, size: 50, ...params }).then(unwrapPage);
export const getPfStatement = (employeeId) =>
  apiClient.get(`/api/v1/hr/pf/statement/${employeeId}`).then(unwrap);
export const recordPfEntry  = (body) => apiClient.post('/api/v1/hr/pf/ledger', body).then(unwrap);

/** A contribution is written by posting payroll; only these are entered by hand. */
export const PF_ENTRY_TYPES = ['WITHDRAWAL', 'ADJUSTMENT', 'SETTLEMENT'];
export const PF_BASES = ['BASIC', 'GROSS'];

/* ═══════════════════════════════════════════════════════════════════════════
   Gratuity

   Same contract: no policy, no figure. The estimate answers configured=false
   rather than zero, because the absence of an answer is not an answer of nought.
   ═══════════════════════════════════════════════════════════════════════════ */

export const getGratuityPolicy        = () => apiClient.get('/api/v1/hr/gratuity/policy').then(unwrap);
export const getGratuityPolicyHistory = () => apiClient.get('/api/v1/hr/gratuity/policy/history').then(unwrap);
export const setGratuityPolicy        = (body) => apiClient.post('/api/v1/hr/gratuity/policy', body).then(unwrap);

export const getGratuityEstimate = (employeeId, asOf) =>
  apiClient.get(`/api/v1/hr/gratuity/estimate/${employeeId}`, asOf ? { asOf } : null).then(unwrap);

export const searchGratuity  = (params = {}) =>
  apiClient.get('/api/v1/hr/gratuity', { page: 0, size: 50, ...params }).then(unwrapPage);
export const getPendingGratuity = () => apiClient.get('/api/v1/hr/gratuity/pending').then(unwrap);

export const awardGratuity   = (body) => apiClient.post('/api/v1/hr/gratuity', body).then(unwrap);
export const submitGratuity  = (id) => apiClient.post(`/api/v1/hr/gratuity/${id}/submit`).then(unwrap);
export const approveGratuity = (id) => apiClient.post(`/api/v1/hr/gratuity/${id}/approve`, {}).then(unwrap);
export const rejectGratuity  = (id, remarks) => apiClient.post(`/api/v1/hr/gratuity/${id}/reject`, { remarks }).then(unwrap);
export const payGratuity     = (id, body) => apiClient.post(`/api/v1/hr/gratuity/${id}/pay`, body).then(unwrap);
export const cancelGratuity  = (id, remarks) => apiClient.post(`/api/v1/hr/gratuity/${id}/cancel`, { remarks }).then(unwrap);

export const GRATUITY_BASES = ['BASIC', 'GROSS'];
export const PARTIAL_YEAR_RULES = [
  { value: 'IGNORE',   label: 'Ignore a part year' },
  { value: 'PRO_RATA', label: 'Count a part year pro rata' },
  { value: 'ROUND_UP', label: 'Round a part year up' },
];

/* ═══════════════════════════════════════════════════════════════════════════
   Expense and conveyance claims

   Paid on their own, never through a payslip: money spent on the pharmacy's
   behalf is being returned, not earned. Putting it in the gross would take tax
   and provident fund out of a rickshaw fare.
   ═══════════════════════════════════════════════════════════════════════════ */

export const getExpenseTypes  = (activeOnly = true) =>
  apiClient.get('/api/v1/hr/expenses/types', { activeOnly }).then(unwrap);
export const createExpenseType = (body) => apiClient.post('/api/v1/hr/expenses/types', body).then(unwrap);
export const updateExpenseType = (id, body) => apiClient.put(`/api/v1/hr/expenses/types/${id}`, body).then(unwrap);
export const retireExpenseType = (id) => apiClient.delete(`/api/v1/hr/expenses/types/${id}`).then(unwrap);

export const searchExpenseClaims = (params = {}) =>
  apiClient.get('/api/v1/hr/expenses', { page: 0, size: 50, ...params }).then(unwrapPage);
export const getPendingExpenses  = () => apiClient.get('/api/v1/hr/expenses/pending').then(unwrap);
export const getExpenseClaim     = (id) => apiClient.get(`/api/v1/hr/expenses/${id}`).then(unwrap);

export const fileExpenseClaim    = (body) => apiClient.post('/api/v1/hr/expenses', body).then(unwrap);
export const updateExpenseClaim  = (id, body) => apiClient.put(`/api/v1/hr/expenses/${id}`, body).then(unwrap);
export const submitExpenseClaim  = (id) => apiClient.post(`/api/v1/hr/expenses/${id}/submit`).then(unwrap);
export const approveExpenseClaim = (id, body) => apiClient.post(`/api/v1/hr/expenses/${id}/approve`, body || {}).then(unwrap);
export const rejectExpenseClaim  = (id, remarks) => apiClient.post(`/api/v1/hr/expenses/${id}/reject`, { remarks }).then(unwrap);
export const payExpenseClaim     = (id, body) => apiClient.post(`/api/v1/hr/expenses/${id}/pay`, body).then(unwrap);
export const cancelExpenseClaim  = (id, remarks) => apiClient.post(`/api/v1/hr/expenses/${id}/cancel`, { remarks }).then(unwrap);

/** A receipt goes up as multipart, the same shape the employee photograph uses. */
export const attachReceipt = (lineId, file) => {
  const form = new FormData();
  form.append('file', file);
  return apiClient.upload(`/api/v1/hr/expenses/lines/${lineId}/receipt`, form).then(unwrap);
};

export const removeReceipt = (lineId) =>
  apiClient.delete(`/api/v1/hr/expenses/lines/${lineId}/receipt`).then(unwrap);

/* ═══════════════════════════════════════════════════════════════════════════
   Approval chains, and the one inbox across every HR workflow

   Nothing is configured out of the box: with no chain, every workflow takes one
   signature exactly as it did before. A chain only ever narrows who may sign.
   ═══════════════════════════════════════════════════════════════════════════ */

export const getWorkflows     = () => apiClient.get('/api/v1/hr/approvals/workflows').then(unwrap);
export const getApprovalFlows = () => apiClient.get('/api/v1/hr/approvals/flows').then(unwrap);
export const createApprovalFlow = (body) => apiClient.post('/api/v1/hr/approvals/flows', body).then(unwrap);
export const updateApprovalFlow = (id, body) => apiClient.put(`/api/v1/hr/approvals/flows/${id}`, body).then(unwrap);
export const retireApprovalFlow = (id) => apiClient.delete(`/api/v1/hr/approvals/flows/${id}`).then(unwrap);

export const getApprovalInbox = () => apiClient.get('/api/v1/hr/approvals/inbox').then(unwrap);
export const getApprovalChain = (workflowCode, refId) =>
  apiClient.get(`/api/v1/hr/approvals/chain/${workflowCode}/${refId}`).then(unwrap);

/** MANAGER is not a role — it is the employee's own manager. */
export const APPROVER_ROLES = [
  { value: 'MANAGER', label: "The employee's own manager" },
  { value: 'ROLE01',  label: 'Super Admin' },
  { value: 'ROLE02',  label: 'Administrator (Branch)' },
  { value: 'ROLE03',  label: 'Pharmacist' },
  { value: 'ROLE04',  label: 'Accounts' },
  { value: 'ROLE05',  label: 'Purchase Manager' },
  { value: 'ROLE09',  label: 'Sales Manager' },
];

/** What each workflow's threshold is counted in, so the form can say. */
export const WORKFLOW_UNIT = {
  LEAVE: 'days', OVERTIME: 'minutes', ADVANCE: 'amount', LOAN: 'amount',
  EXPENSE: 'amount', INCREMENT: 'amount', GRATUITY: 'amount',
};

/** Where a workflow's own screen lives, so an inbox row can be opened. */
export const WORKFLOW_PAGE = {
  LEAVE: 'leave', OVERTIME: 'attendance', ADVANCE: 'advances', LOAN: 'advances',
  EXPENSE: 'expenses', INCREMENT: 'hr', GRATUITY: 'hr',
};

/* ═══ The HR calendar ═══════════════════════════════════════════════════════
   A view over rows that already exist — holidays, approved leave, birthdays,
   anniversaries, probation ending, increments. Nothing is stored twice.
   ═══════════════════════════════════════════════════════════════════════════ */

export const getHrCalendar = (from, to, employeeId) =>
  apiClient.get('/api/v1/hr/attendance/hr-calendar',
                { from, to, ...(employeeId ? { employeeId } : {}) }).then(unwrap);

export const CALENDAR_EVENT_LABEL = {
  HOLIDAY: 'Holiday', LEAVE: 'Leave', BIRTHDAY: 'Birthday',
  ANNIVERSARY: 'Anniversary', CONFIRMATION: 'Probation ends', INCREMENT: 'Increment',
};
