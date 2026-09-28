/**
 * The driver's end of an online order — docs/ecommerce Phase 10.
 *
 * Two things the ERP never recorded: where the order is while it is out, and
 * evidence that it was handed over. Both are refused by the server unless the
 * caller is the driver the order is assigned to and the order is actually out,
 * so nothing here needs to guess at those rules.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/ecom/admin/delivery';

const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** Reports one position. Rejected by the server if the reading is too vague to mean anything. */
export const reportPosition = (orderNo, { latitude, longitude, accuracyM }) =>
  apiClient.post(`${BASE}/${orderNo}/position`, { latitude, longitude, accuracyM }).then(unwrap);

/**
 * Records what happened at the door: a photograph, a name, or a note.
 *
 * Sent as multipart because a photograph may come with it; the same call
 * without one records the name or the note alone.
 */
export async function recordProof(orderNo, { receivedByName, note, latitude, longitude, photo } = {}) {
  const form = new FormData();
  if (receivedByName) form.append('receivedByName', receivedByName);
  if (note) form.append('note', note);
  if (latitude != null) form.append('latitude', String(latitude));
  if (longitude != null) form.append('longitude', String(longitude));
  if (photo) form.append('photo', photo);
  return unwrap(await apiClient.upload(`${BASE}/${orderNo}/proof`, form));
}

/**
 * The browser's own position, once.
 *
 * Resolves with what the device claims, including how accurate it thinks it is
 * — the server refuses a reading too vague to show anybody, and it can only do
 * that if the accuracy travels with the point.
 */
export function currentPosition({ timeout = 10000 } = {}) {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('This device cannot share a location.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({
        latitude: Number(p.coords.latitude.toFixed(6)),
        longitude: Number(p.coords.longitude.toFixed(6)),
        accuracyM: p.coords.accuracy == null ? null : Math.round(p.coords.accuracy),
      }),
      (err) => reject(new Error(
        err.code === err.PERMISSION_DENIED
          ? 'Location is switched off for this site. Turn it on to share where the delivery is.'
          : 'Your location could not be read just now.')),
      { enableHighAccuracy: true, timeout, maximumAge: 5000 },
    );
  });
}

export default { reportPosition, recordProof, currentPosition };
