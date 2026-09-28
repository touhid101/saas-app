# 18 — Employee Image Upload

**Status: implemented and verified.** 22 end-to-end checks against the running
application, covering the happy path and — more to the point — the rejections.

---

## 1. Where the file goes

```
app.upload.root              ${UPLOAD_ROOT:uploads}
app.upload.employee-image-dir        ${UPLOAD_EMPLOYEE_IMAGE_DIR:<root>/employee_image}
app.upload.employee-image-url-prefix ${UPLOAD_EMPLOYEE_IMAGE_URL_PREFIX:/uploads/employee_image/}
```

* An **absolute** `UPLOAD_ROOT` is used exactly as given.
* A **relative** one anchors to the user's home directory — never inside the
  deployment, which a redeploy would wipe.
* Nothing is hard-coded. There is no `C:/…`, no `/data/…`, and no literal
  `/uploads` in any Java file.

Confirmed at startup on this machine:

```
Employee image upload directory ready: D:\…\Pharma_apps\upload\employee_image
Static resource handler: /uploads/employee_image/** -> D:\…\upload\employee_image
Upload directory ready (EMPLOYEE_IMAGE): D:\…\upload\employee_image
```

`UPLOAD_ROOT` is set in `backend/.env`; changing it moves all four buckets
together, and the application prints every resolved path at startup so nobody
has to work it out from the configuration.

## 2. What the database holds

`hr_employees.image_path VARCHAR(300)` — a **public URL relative to the
application**:

```
/uploads/employee_image/employee_30_042ff28eec7d48279ada615e3ef3a91c.png
```

Never a filesystem path. Where the folder sits is configuration, and a
machine-specific path in a row would break the moment the application moved.

The pre-existing `emp_image` (a large object), `file_name` and `file_mime_type`
columns are **kept, not dropped**. Nothing writes them, but a production row
might hold the only copy of a photograph; removing them is a separate decision
once they are known to be empty.

## 3. Naming

```
employee_<employeeId>_<32 hex UUID>.<ext>
```

The employee id is there so the directory is readable to a human. **Uniqueness
comes from the UUID alone** — nothing reads the id back out of a filename.

**The original filename is discarded entirely.** It is never used, never
sanitised and never stored, which is why `../../evil.png` is not a special case:
it produces an ordinary generated name inside the root like everything else.

## 4. Validation — five independent checks

Reused wholesale from `ImageStorageService`, which already guarded avatars and
branch logos. Adding a sixth bucket added no new validation code, which is the
point of not writing a second uploader.

| # | Check | Rejection |
|---|---|---|
| 1 | Size against `app.upload.max-size-bytes` (2 MB) | `FILE_TOO_LARGE` · 413 |
| 2 | Declared MIME in `{image/jpeg, image/png, image/webp}` | `INVALID_FILE_TYPE` · 400 |
| 3 | Extension in `{jpg, jpeg, png, webp}` | `INVALID_FILE_EXTENSION` · 400 |
| 4 | MIME and extension **agree** | `FILE_TYPE_MISMATCH` · 400 |
| 5 | Leading bytes are a real JPEG/PNG/WEBP signature | `INVALID_IMAGE_CONTENT` · 400 |

Then the resolved path is re-checked with `target.startsWith(root)` before
anything is written.

**The browser's declared type is never trusted on its own** — checks 3, 4 and 5
exist precisely because it can be set to anything.

**SVG is deliberately absent from the whitelist.** It is an image everywhere
except in a browser, where it is a document that can carry script — and these
directories are served over HTTP.

## 5. Who may change whose photograph

Either **Edit on Employees (MOD32)**, or **the employee themselves**.

The self case is checked against `ACL_USER.PID_EMPLOYEE` — the link that already
ties a login to a staff record — via `SecurityUtils.getCurrentEmployeeId()`,
never against anything the client sends. It is what makes self-service possible
without granting every employee the right to edit the whole staff list.

Branch isolation comes first: `HrScope.employee` refuses an employee from
another branch as a **404, not a 403**, so a caller at another pharmacy cannot
learn that the employee exists.

## 6. Replacing and removing

The old file is deleted **after** the row is updated, never before. Deleting
first and then failing to save would leave a row pointing at a file that is
gone — a broken image on every screen showing that person. Deleting afterwards
can at worst orphan a file, which costs disk and nothing else. The delete is
best-effort for the same reason and never fails the transaction.

## 7. The UI contract

Every response that carries `imagePath` also carries `initials`, computed
server-side from the preferred name or the legal name. A client therefore never
has to choose between an empty `src` and a broken-image glyph — it has two
letters to draw before the first image request is made.

`EmployeeAvatar.jsx` falls back to initials on all three failure modes: no
photograph, a file deleted from under the row, and a request that fails. It
resets its failed flag when `src` changes, or replacing a photo would leave the
old failure latched and the new image would never render.

Shown in: the staff list, the employee profile header. **Not yet** in attendance,
leave approval, payroll or self-service — those screens exist but do not yet
render the avatar.

## 8. Verification

`scratchpad/verify-employee-image.mjs`, 22 checks, all passing:

| | |
|---|---|
| ✅ | valid PNG accepted; response carries the URL |
| ✅ | the URL is relative, not a filesystem path |
| ✅ | the stored filename is generated, not the uploaded one |
| ✅ | the URL actually serves the image back (`200 image/png`) |
| ✅ | the profile returns it, with initials alongside |
| ✅ | replacing works, **and the previous file 404s afterwards** |
| ✅ | `.exe` extension refused |
| ✅ | MIME disagreeing with extension refused |
| ✅ | a PHP script renamed `.png` refused **on its content** |
| ✅ | SVG refused |
| ✅ | `../../evil.png` cannot escape the root |
| ✅ | 3 MB file refused |
| ✅ | request with no file refused — **400, after a fix; it was 500** |
| ✅ | removal clears the row and deletes the file; initials remain |

One real defect was found and fixed by this suite: a multipart request missing
its file part fell through to the catch-all and answered **500**, which says the
server broke when the request was simply incomplete. `MissingServletRequestPartException`
now maps to 400 `MISSING_FILE`.

## 9. Not done

* **Thumbnails.** Full images are served with a one-hour cache and `loading="lazy"`.
  The existing architecture has no resize step, and adding one is a separate change.
* **Retention of replaced files.** The old file is deleted immediately. If a
  retention policy is wanted, that is a business decision — nothing here archives.
* **Avatars on attendance, leave approval, payroll and self-service screens.**
