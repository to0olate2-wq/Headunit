// Original modern premium saloon (low fastback, black glass canopy, light bars, 21-inch wheels).
// Built from profile curves as a full closed 3D body, rendered side-on (driving) and three-quarter front (parked).
import * as THREE from 'three';

const L = 4.98;
const H = 1.60;
const WR = 0.98, WF = 4.03, TIRE_R = 0.37, WY = 0.37;
const ARCH_R = 0.40, ARCH_Y = 0.386;

function spline(pts) {
  const n = pts.length, xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
  const d = [], m = new Array(n);
  for (let i = 0; i < n - 1; i++) d.push((ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]));
  m[0] = d[0]; m[n - 1] = d[n - 2];
  for (let i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
  for (let i = 0; i < n - 1; i++) {
    if (d[i] === 0) { m[i] = 0; m[i + 1] = 0; continue; }
    const a = m[i] / d[i], b = m[i + 1] / d[i], s = a * a + b * b;
    if (s > 9) { const t = 3 / Math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
  }
  return x => {
    if (x <= xs[0]) return ys[0];
    if (x >= xs[n - 1]) return ys[n - 1];
    let i = 0; while (x > xs[i + 1]) i++;
    const h = xs[i + 1] - xs[i], t = (x - xs[i]) / h, t2 = t * t, t3 = t2 * t;
    return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1];
  };
}
const smooth = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };

// ---------------------------------------------------------------- profile
const yBot = spline([[0, 0.44], [0.05, 0.35], [0.18, 0.30], [0.45, 0.26], [0.9, 0.205], [1.45, 0.17], [3.5, 0.17],
  [4.2, 0.195], [4.58, 0.24], [4.82, 0.29], [4.94, 0.34], [L, 0.40]]);
const yTop = spline([[0, 0.88], [0.035, 0.965], [0.10, 1.012], [0.20, 1.03], [0.34, 1.045], [0.6, 1.105], [0.95, 1.215],
  [1.35, 1.325], [1.8, 1.395], [2.2, 1.418], [2.55, 1.41], [2.85, 1.36], [3.15, 1.225], [3.42, 1.055], [3.62, 0.962],
  [4.0, 0.93], [4.4, 0.89], [4.68, 0.845], [4.86, 0.80], [4.95, 0.74], [L, 0.67]]);
const CAB0 = 0.36, CAB1 = 3.6;   // where the glass canopy starts and ends
const beltS = spline([[CAB0, 1.03], [0.9, 1.035], [1.6, 1.018], [2.6, 0.985], [3.3, 0.965], [CAB1, 0.948]]);
const CROWN_DECK = 0.05, CROWN_ROOF = 0.032;
function crown(x) { return (CROWN_DECK + (CROWN_ROOF - CROWN_DECK) * smooth(CAB0, CAB0 + 0.5, x) * (1 - smooth(CAB1 - 0.35, CAB1, x))) * (1 - 0.75 * smooth(L - 0.55, L, x)) * (1 - 0.6 * smooth(0.45, 0.0, x)); }
function yBelt(x) {
  const top = yTop(x) - crown(x);
  if (x <= CAB0 || x >= CAB1) return top;
  return Math.min(top, beltS(x));
}
function halfW(x) {
  const W = 0.975;
  if (x > 4.30) { const u = Math.min(1, (x - 4.30) / (L - 4.30)); return W * Math.pow(Math.max(0, 1 - Math.pow(u, 3.2)), 1 / 3.2); }
  if (x < 0.70) { const u = Math.min(1, (0.70 - x) / 0.70); return W * Math.pow(Math.max(0, 1 - Math.pow(u, 3.0)), 1 / 3.0); }
  return W;
}
// haunches over the wheels
const haunch = x => 0.022 * Math.exp(-Math.pow((x - WR) / 0.55, 2)) + 0.014 * Math.exp(-Math.pow((x - WF) / 0.5, 2));

// ---------------------------------------------------------------- body (full closed shell)
const NF = 3, NS = 30, NG = 14, NE = 6, NC = 12;
const HALF = NF + NS + NG + NE + NC;          // points per half ring
const SIDE0 = NF + 2, SIDE1 = NF + Math.round(NS * 0.86);   // rows used for side decals
function halfRing(x) {
  const yb = yBot(x), hw = halfW(x), top = yTop(x), cr = crown(x);
  const belt = yBelt(x);
  const gh = Math.max(0, top - cr - belt);            // canopy height at this station
  const cab = smooth(0.0, 0.06, gh);
  const pts = [];
  for (let k = 0; k < NF; k++) pts.push([yb, hw * 0.78 * (k / (NF - 1))]);
  const shoulderTop = belt;
  for (let k = 1; k <= NS; k++) {
    const s = k / NS;
    let f;
    if (s < 0.30) f = 0.77 + 0.23 * Math.sin((s / 0.30) * Math.PI / 2);
    else if (s < 0.56) f = 1.0 - 0.024 * Math.sin(((s - 0.30) / 0.26) * Math.PI) + haunch(x) * Math.sin(((s - 0.30) / 0.26) * Math.PI);
    else if (s < 0.84) f = 1.0 + haunch(x) * 0.6 * (1 - (s - 0.56) / 0.28);
    else f = 1.0 - 0.095 * Math.pow((s - 0.84) / 0.16, 1.7);
    // near the nose and the tail the side sculpting blends into a plain rounded section
    const mid = smooth(0.0, 0.95, x) * (1 - smooth(L - 0.85, L, x));
    const fe = 0.82 + 0.18 * Math.pow(Math.sin(Math.PI * Math.min(1, s * 1.05)), 0.45);
    f = fe + (f - fe) * mid;
    pts.push([yb + s * (shoulderTop - yb), hw * f]);
  }
  const zBelt = hw * 0.905;
  for (let k = 1; k <= NG; k++) {
    const t = k / NG;
    pts.push([belt + t * Math.max(0, gh - 0.045 * cab), zBelt + (hw * (0.905 - 0.205 * t) - zBelt) * cab]);
  }
  const yEdge0 = belt + Math.max(0, gh - 0.045 * cab);
  const zEdge0 = zBelt + (hw * 0.70 - zBelt) * cab;
  const r = 0.045 * cab + 0.03 * (1 - cab);
  for (let k = 1; k <= NE; k++) {
    const a = (k / NE) * Math.PI / 2;
    pts.push([yEdge0 + Math.sin(a) * (top - cr - yEdge0 + 0.012 * cab), zEdge0 - r * (1 - Math.cos(a))]);
  }
  const yE = pts[pts.length - 1][0], zE = pts[pts.length - 1][1];
  for (let k = 1; k <= NC; k++) {
    const t = k / NC;
    pts.push([yE + (top - yE) * (1 - (1 - t) * (1 - t)), zE * (1 - t)]);
  }
  return { pts, cab };
}

function bodyGeometry() {
  const NX = 360;
  const pos = [], uvSide = [], uvFront = [];
  const cabs = [];
  const RING = HALF * 2;
  for (let i = 0; i <= NX; i++) {
    const x = (i / NX) * L;
    const { pts, cab } = halfRing(x);
    cabs.push(cab);
    // right half (bottom centre → top centre), then left half (top centre → bottom centre)
    const full = [...pts.map(([y, z]) => [y, z]), ...pts.slice().reverse().map(([y, z]) => [y, -z])];
    for (const [y, z] of full) {
      pos.push(x, y, z);
      uvSide.push(x / L, y / H);
      uvFront.push(0.5 + z / 2.2, y / H);
    }
  }
  const body = [], glass = [];
  const glassRow = k => { const h = k < HALF ? k : RING - 1 - k; return h >= NF + NS; };
  for (let i = 0; i < NX; i++) for (let k = 0; k < RING - 1; k++) {
    const a = i * RING + k, b = a + 1, c = a + RING, d = c + 1;
    const isGlass = glassRow(k) && glassRow(k + 1) && cabs[i] > 0.5 && cabs[i + 1] > 0.5;
    (isGlass ? glass : body).push(a, c, b, b, c, d);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uvSide, 2));
  g.setAttribute('uv1', new THREE.Float32BufferAttribute(uvFront, 2));
  g.setIndex([...body, ...glass]);
  g.addGroup(0, body.length, 0);
  g.addGroup(body.length, glass.length, 1);
  g.computeVertexNormals();
  g.userData = { NX, RING };
  return g;
}

/** Sub-mesh of the body (rows k0..k1 of each half, stations in [x0, x1]) pushed out along the normals, with chosen uv. */
function patch(g, k0, k1, x0, x1, d, uvName) {
  const { NX, RING } = g.userData;
  const P = g.attributes.position, N = g.attributes.normal, U = g.attributes[uvName];
  const pos = [], uv = [], idx = [];
  const rows = [];
  for (let k = k0; k <= k1; k++) rows.push(k);
  for (let k = RING - 1 - k1; k <= RING - 1 - k0; k++) rows.push(k);
  const map = new Map();
  let n = 0;
  for (let i = 0; i <= NX; i++) {
    const x = (i / NX) * L;
    if (x < x0 - 0.02 || x > x1 + 0.02) continue;
    for (const k of rows) {
      const v = i * RING + k;
      map.set(v, n++);
      pos.push(P.getX(v) + N.getX(v) * d, P.getY(v) + N.getY(v) * d, P.getZ(v) + N.getZ(v) * d);
      uv.push(U.getX(v), U.getY(v));
    }
  }
  for (let i = 0; i < NX; i++) for (let k = 0; k < RING - 1; k++) {
    const a = i * RING + k, b = a + 1, c = a + RING, dd = c + 1;
    if ([a, b, c, dd].every(v => map.has(v))) idx.push(map.get(a), map.get(c), map.get(b), map.get(b), map.get(c), map.get(dd));
  }
  const o = new THREE.BufferGeometry();
  o.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  o.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  o.setIndex(idx);
  o.computeVertexNormals();
  return o;
}

// point on the body surface (right side) at station x and height y (on the lower body), for trims and lamps
function sidePoint(x, y) {
  const { pts } = halfRing(x);
  let best = pts[0], bd = 1e9;
  for (let k = 0; k < NF + NS; k++) { const dd = Math.abs(pts[k][0] - y); if (dd < bd) { bd = dd; best = pts[k]; } }
  return best[1];
}

// ---------------------------------------------------------------- canvas masks
function canvasTex(w, h, draw) {
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  const g = c.getContext('2d');
  g.fillStyle = '#000'; g.fillRect(0, 0, w, h);
  g.fillStyle = '#fff'; g.strokeStyle = '#fff'; g.lineJoin = 'round'; g.lineCap = 'round';
  draw(g, w, h);
  const t = new THREE.CanvasTexture(c); t.anisotropy = 8; return t;
}
const SW = 4096, SH = Math.round(4096 * H / L);
const sx = x => x / L * SW, sy = y => (1 - y / H) * SH, sm = m => m / L * SW;
function sideMask(draw) { return canvasTex(SW, SH, draw); }
const FW = 2048, FH = Math.round(2048 * H / 2.2);
const fx = z => (0.5 + z / 2.2) * FW, fy = y => (1 - y / H) * FH, fm = m => m / 2.2 * FW;
function frontMask(draw) { return canvasTex(FW, FH, draw); }

const masks = {};
const mk = (k, f) => masks[k] || (masks[k] = f());

const paintMask = () => sideMask(g => {
  g.fillRect(0, 0, SW, SH); g.fillStyle = '#000';
  for (const cx of [WR, WF]) { g.beginPath(); g.arc(sx(cx), sy(ARCH_Y), sm(ARCH_R), 0, Math.PI * 2); g.fill(); }
});
const gapMask = () => sideMask(g => {
  g.lineWidth = sm(0.004);
  const line = pts => { g.beginPath(); pts.forEach(([x, y], i) => i ? g.lineTo(sx(x), sy(y)) : g.moveTo(sx(x), sy(y))); g.stroke(); };
  line([[3.50, yBelt(3.50) - 0.01], [3.53, 0.70], [3.50, 0.40], [3.46, 0.27]]);        // front door leading edge
  line([[2.25, yBelt(2.25) - 0.01], [2.27, 0.26]]);                                    // between doors
  g.beginPath(); g.moveTo(sx(1.12), sy(yBelt(1.12) - 0.01)); g.lineTo(sx(1.30), sy(0.78));
  g.arc(sx(WR), sy(ARCH_Y), sm(ARCH_R + 0.075), -Math.acos((1.30 - WR) / (ARCH_R + 0.075)) * 0.999, -0.04, false); g.stroke();
  line([[3.46, 0.265], [1.48, 0.265]]);
  // flush door handles
  for (const hx of [2.95, 1.75]) { g.beginPath(); g.roundRect(sx(hx - 0.12), sy(0.925), sm(0.24), sm(0.028), sm(0.014)); g.stroke(); }
});
const handleMask = () => sideMask(g => { for (const hx of [2.95, 1.75]) { g.beginPath(); g.roundRect(sx(hx - 0.115), sy(0.922), sm(0.23), sm(0.022), sm(0.011)); g.fill(); } });
const skirtMask = () => sideMask(g => {
  g.beginPath();
  for (let x = 1.42; x <= 3.62; x += 0.02) { const y = yBot(x) + 0.085; x === 1.42 ? g.moveTo(sx(x), sy(y)) : g.lineTo(sx(x), sy(y)); }
  g.lineTo(sx(3.62), sy(0.0)); g.lineTo(sx(1.42), sy(0.0)); g.closePath(); g.fill();
  // rear diffuser and front splitter seen from the side
  g.beginPath(); g.moveTo(sx(0.0), sy(0.40)); g.lineTo(sx(0.45), sy(0.33)); g.lineTo(sx(0.55), sy(0.0)); g.lineTo(sx(0), sy(0)); g.closePath(); g.fill();
  g.beginPath(); g.moveTo(sx(L), sy(0.38)); g.lineTo(sx(4.62), sy(0.30)); g.lineTo(sx(4.55), sy(0.0)); g.lineTo(sx(L), sy(0)); g.closePath(); g.fill();
});
// front face: black panel, light bar, lower intake
const frontPanel = () => frontMask(g => {
  // slim black band carrying the light bar, wider headlight units at the corners, low intake
  g.beginPath(); g.roundRect(fx(-0.70), fy(0.665), fm(1.40), fm(0.05), fm(0.025)); g.fill();
  for (const s of [-1, 1]) {
    g.beginPath(); g.moveTo(fx(s * 0.50), fy(0.665)); g.lineTo(fx(s * 0.93), fy(0.70)); g.lineTo(fx(s * 0.95), fy(0.615)); g.lineTo(fx(s * 0.52), fy(0.615)); g.closePath(); g.fill();
  }
  g.beginPath(); g.roundRect(fx(-0.66), fy(0.475), fm(1.32), fm(0.085), fm(0.04)); g.fill();
});
const frontLeds = () => frontMask(g => {
  g.lineWidth = fm(0.012);
  g.beginPath(); g.moveTo(fx(-0.66), fy(0.642)); g.lineTo(fx(0.66), fy(0.642)); g.stroke();
  for (const s of [-1, 1]) { g.beginPath(); g.moveTo(fx(s * 0.58), fy(0.648)); g.lineTo(fx(s * 0.92), fy(0.672)); g.stroke(); }
});
const frontBar = () => frontMask(g => { g.lineWidth = fm(0.016); g.beginPath(); g.moveTo(fx(-0.84), fy(0.715)); g.lineTo(fx(0.84), fy(0.715)); g.stroke(); });
const rearPanel = () => frontMask(g => {
  g.beginPath(); g.roundRect(fx(-0.86), fy(0.975), fm(1.72), fm(0.085), fm(0.03)); g.fill();
  g.beginPath(); g.roundRect(fx(-0.70), fy(0.42), fm(1.40), fm(0.12), fm(0.03)); g.fill();
});
const rearBar = () => frontMask(g => { g.lineWidth = fm(0.022); g.beginPath(); g.moveTo(fx(-0.88), fy(0.935)); g.lineTo(fx(0.88), fy(0.935)); g.stroke(); });

// ---------------------------------------------------------------- studio
function studioEnv(renderer) {
  const scene = new THREE.Scene();
  const geo = new THREE.SphereGeometry(40, 64, 32);
  const col = [], p = geo.attributes.position;
  const sky = new THREE.Color(0.32, 0.34, 0.38), hor = new THREE.Color(0.17, 0.18, 0.20), gnd = new THREE.Color(0.02, 0.022, 0.026);
  for (let i = 0; i < p.count; i++) {
    const y = p.getY(i) / 40;
    const c = y > 0 ? hor.clone().lerp(sky, Math.pow(y, 0.6)) : hor.clone().lerp(gnd, Math.min(1, -y * 6));
    col.push(c.r, c.g, c.b);
  }
  geo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  scene.add(new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.BackSide })));
  const box = (w, h, pos, rot, k) => {
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(k, k, k), side: THREE.DoubleSide }));
    m.position.set(...pos); m.rotation.set(...rot); scene.add(m);
  };
  box(14, 30, [2.5, 16, 0], [-Math.PI / 2, 0, 0], 3.2);          // big overhead softbox
  box(70, 1.4, [0, 2.2, 18], [0, 0, 0], 6);                        // low strip, right side
  box(70, 1.4, [0, 2.2, -18], [0, 0, 0], 6);                       // low strip, left side
  box(70, 4, [0, 7.5, 17], [0, 0, 0], 1.6);
  box(70, 4, [0, 7.5, -17], [0, 0, 0], 1.6);
  box(1.4, 40, [22, 3, 0], [0, Math.PI / 2, 0], 4);               // strips in front and behind
  box(1.4, 40, [-20, 3, 0], [0, Math.PI / 2, 0], 3);
  const pm = new THREE.PMREMGenerator(renderer);
  return pm.fromScene(scene, 0.02).texture;
}

// ---------------------------------------------------------------- wheel
function wheelGroup() {
  const g = new THREE.Group();
  const rin = 0.272, rout = TIRE_R, w = 0.265;
  const prof = [new THREE.Vector2(rin, -w / 2)];
  for (let i = 0; i <= 24; i++) {
    const a = -Math.PI / 2 + (i / 24) * Math.PI;
    prof.push(new THREE.Vector2(Math.max(rin, rout - 0.035 + 0.035 * Math.cos(a)), (w / 2) * Math.sin(a)));
  }
  prof.push(new THREE.Vector2(rin, w / 2));
  const tyre = new THREE.Mesh(new THREE.LatheGeometry(prof, 128),
    new THREE.MeshPhysicalMaterial({ color: 0x121212, roughness: 0.85, sheen: 0.4, sheenColor: 0x3a3a3a, sheenRoughness: 0.6 }));
  tyre.rotation.x = Math.PI / 2; g.add(tyre);
  const wall = new THREE.Mesh(new THREE.TorusGeometry(0.318, 0.04, 24, 128), new THREE.MeshPhysicalMaterial({ color: 0x141414, roughness: 0.8 }));
  wall.scale.z = 0.55; wall.position.z = w / 2 - 0.022; g.add(wall);
  const barrel = new THREE.Mesh(new THREE.CylinderGeometry(0.262, 0.262, 0.22, 96, 1, true),
    new THREE.MeshStandardMaterial({ color: 0x1d1f23, metalness: 0.8, roughness: 0.5, side: THREE.DoubleSide }));
  barrel.rotation.x = Math.PI / 2; g.add(barrel);
  const disc = new THREE.Mesh(new THREE.CylinderGeometry(0.2, 0.2, 0.03, 96), new THREE.MeshStandardMaterial({ color: 0x80858d, metalness: 0.9, roughness: 0.4 }));
  disc.rotation.x = Math.PI / 2; disc.position.z = 0.02; g.add(disc);
  const caliper = new THREE.Mesh(new THREE.TorusGeometry(0.175, 0.035, 16, 32, 0.95),
    new THREE.MeshPhysicalMaterial({ color: 0x1a1b1e, metalness: 0.3, roughness: 0.35, clearcoat: 1 }));
  caliper.rotation.z = Math.PI * 0.6; caliper.position.z = 0.055; caliper.scale.z = 1.4; g.add(caliper);
  // turbine-style rim: 12 slim curved spokes
  const face = new THREE.Shape();
  face.absarc(0, 0, 0.262, 0, Math.PI * 2, false);
  const N = 12;
  for (let s = 0; s < N; s++) {
    const a0 = (s / N) * Math.PI * 2, a1 = a0 + (Math.PI * 2 / N) - 0.11;
    const h = new THREE.Path();
    const r0 = 0.09, r1 = 0.238, twist = 0.22;
    const P = (r, a) => [Math.cos(a) * r, Math.sin(a) * r];
    h.moveTo(...P(r0, a0 + 0.05));
    for (let t = 0; t <= 1.0001; t += 0.1) h.lineTo(...P(r0 + (r1 - r0) * t, a0 + 0.05 + twist * t));
    for (let t = 0; t <= 1.0001; t += 0.1) h.lineTo(...P(r1, a0 + 0.05 + twist + (a1 - a0 - 0.05) * t));
    for (let t = 1; t >= -0.0001; t -= 0.1) h.lineTo(...P(r0 + (r1 - r0) * t, a1 + twist * t));
    h.closePath();
    face.holes.push(h);
  }
  const rimGeo = new THREE.ExtrudeGeometry(face, { depth: 0.03, bevelEnabled: true, bevelThickness: 0.008, bevelSize: 0.005, bevelSegments: 4, curveSegments: 48 });
  const rimMat = new THREE.MeshPhysicalMaterial({ color: 0x8c929a, metalness: 1.0, roughness: 0.26, clearcoat: 0.8, clearcoatRoughness: 0.08 });
  const rim = new THREE.Mesh(rimGeo, rimMat); rim.position.z = 0.092; g.add(rim);
  const lip = new THREE.Mesh(new THREE.TorusGeometry(0.262, 0.007, 12, 128), new THREE.MeshPhysicalMaterial({ color: 0xd9dde3, metalness: 1, roughness: 0.15 }));
  lip.position.z = 0.128; g.add(lip);
  const cap = new THREE.Mesh(new THREE.CylinderGeometry(0.045, 0.05, 0.024, 48), new THREE.MeshPhysicalMaterial({ color: 0x111214, metalness: 0.5, roughness: 0.2, clearcoat: 1 }));
  cap.rotation.x = Math.PI / 2; cap.position.z = 0.128; g.add(cap);
  return g;
}

// ---------------------------------------------------------------- car
function lampTube(x0, y, side, color, emissive, radius) {
  // follows the body around the nose (front) or tail (rear) at height y
  const pts = [];
  const N = 60;
  for (let i = 0; i <= N; i++) {
    const t = i / N;               // 0: right side, 1: left side
    const xs = side > 0 ? x0 + (L - x0) * Math.sin(t * Math.PI) : x0 * (1 - Math.sin(t * Math.PI));
    const xx = side > 0 ? Math.min(L - 0.004, xs) : Math.max(0.004, xs);
    const sg = t < 0.5 ? 1 : (t > 0.5 ? -1 : 0);
    const z = sg * (sidePoint(xx, y) + 0.004);
    pts.push(new THREE.Vector3(xx + side * 0.004, y, z));
  }
  const curve = new THREE.CatmullRomCurve3(pts);
  const mat = emissive ? new THREE.MeshBasicMaterial({ color }) : new THREE.MeshPhysicalMaterial({ color, roughness: 0.1, metalness: 0.3, clearcoat: 1 });
  return new THREE.Mesh(new THREE.TubeGeometry(curve, 160, radius, 10, false), mat);
}

function beltTrim(side) {
  const pts = [];
  for (let x = CAB0 + 0.55; x <= CAB1 - 0.08; x += 0.04) {
    const { pts: ring } = halfRing(x);
    const p = ring[NF + NS - 1];
    pts.push(new THREE.Vector3(x, p[0] + 0.004, side * (p[1] + 0.006)));
  }
  return new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 200, 0.0065, 8, false),
    new THREE.MeshPhysicalMaterial({ color: 0xc9ced6, metalness: 1, roughness: 0.22 }));
}

function carGroup(paint, opts) {
  const grp = new THREE.Group();
  const body = bodyGeometry();
  const paintMat = new THREE.MeshPhysicalMaterial({
    color: new THREE.Color(paint.color), metalness: paint.metal, roughness: paint.rough, clearcoat: 1.0, clearcoatRoughness: 0.03,
    alphaMap: mk('paint', paintMask), alphaTest: 0.5, envMapIntensity: paint.env ?? 1.0, side: THREE.DoubleSide,
  });
  const glassMat = new THREE.MeshPhysicalMaterial({ color: 0x040507, roughness: 0.05, metalness: 0.0, clearcoat: 1, clearcoatRoughness: 0.02, envMapIntensity: 0.25 });
  grp.add(new THREE.Mesh(body, [paintMat, glassMat]));
  const layer = (geo, mat, m, order) => { mat.alphaMap = m; mat.transparent = true; mat.depthWrite = false; const me = new THREE.Mesh(geo, mat); me.renderOrder = order; grp.add(me); };
  const sideRows = (d) => patch(body, SIDE0, SIDE1, 0.05, L - 0.05, d, 'uv');
  layer(sideRows(0.0012), new THREE.MeshPhysicalMaterial({ color: 0x050607, roughness: 0.7 }), mk('gap', gapMask), 1);
  layer(sideRows(0.0020), new THREE.MeshPhysicalMaterial({ color: 0x0b0c0e, roughness: 0.25, metalness: 0.2, clearcoat: 1 }), mk('skirt', skirtMask), 2);
  layer(sideRows(0.0026), new THREE.MeshPhysicalMaterial({ color: 0xd8dde3, roughness: 0.18, metalness: 1 }), mk('handle', handleMask), 3);
  // front and rear faces (projection from the front)
  const nose = (d) => patch(body, 0, NF + NS, L - 0.42, L, d, 'uv1');
  const tail = (d) => patch(body, 0, NF + NS, 0, 0.40, d, 'uv1');
  layer(nose(0.002), new THREE.MeshPhysicalMaterial({ color: 0x06070a, roughness: 0.06, metalness: 0.4, clearcoat: 1 }), mk('fp', frontPanel), 4);
  layer(nose(0.0032), new THREE.MeshBasicMaterial({ color: new THREE.Color(opts.drl ?? 0.95, opts.drl ?? 0.97, opts.drl ?? 1.0) }), mk('fl', frontLeds), 5);
  layer(tail(0.002), new THREE.MeshPhysicalMaterial({ color: 0x0a0607, roughness: 0.06, metalness: 0.4, clearcoat: 1 }), mk('rp', rearPanel), 4);
  // light bars wrap around the corners
  grp.add(lampTube(L - 0.30, 0.66, 1, new THREE.Color(opts.drl ?? 0.95, opts.drl ?? 0.97, opts.drl ?? 1.0), true, 0.006));
  grp.add(lampTube(0.40, 0.935, -1, new THREE.Color(opts.tail ?? 1.0, 0.09, 0.12), true, 0.011));
  grp.add(beltTrim(1)); grp.add(beltTrim(-1));
  // wheel wells
  for (const cx of [WR, WF]) {
    const well = new THREE.Mesh(new THREE.CylinderGeometry(ARCH_R + 0.012, ARCH_R + 0.012, 1.92, 64, 1, true, 0, Math.PI * 2),
      new THREE.MeshStandardMaterial({ color: 0x040405, roughness: 0.95, side: THREE.BackSide }));
    well.rotation.x = Math.PI / 2; well.position.set(cx, ARCH_Y, 0); grp.add(well);
  }
  // mirrors
  for (const s of [1, -1]) {
    const mir = new THREE.Mesh(new THREE.SphereGeometry(1, 48, 24), new THREE.MeshPhysicalMaterial({ color: 0x0a0b0d, roughness: 0.08, metalness: 0.3, clearcoat: 1 }));
    mir.scale.set(0.11, 0.05, 0.08); mir.position.set(3.36, 1.03, s * (halfW(3.36) * 0.905 + 0.11)); mir.rotation.y = s * 0.15; grp.add(mir);
    const arm = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.02, 0.1), new THREE.MeshPhysicalMaterial({ color: 0x0a0b0d, roughness: 0.3 }));
    arm.position.set(3.38, 1.00, s * (halfW(3.36) * 0.905 + 0.045)); grp.add(arm);
  }
  if (opts.wheels) for (const cx of [WR, WF]) for (const s of [1, -1]) {
    const w = wheelGroup(); w.position.set(cx, WY, s * (halfW(cx) - 0.145));
    if (s < 0) w.rotation.y = Math.PI;
    grp.add(w);
  }
  return grp;
}

function shadowPlane() {
  const tex = canvasTex(512, 256, (g, w, h) => {
    g.fillStyle = '#000'; g.fillRect(0, 0, w, h);
    g.filter = 'blur(18px)'; g.fillStyle = '#fff'; g.beginPath(); g.roundRect(w * 0.08, h * 0.2, w * 0.84, h * 0.6, h * 0.25); g.fill();
    g.filter = 'blur(6px)'; g.beginPath(); g.roundRect(w * 0.12, h * 0.3, w * 0.76, h * 0.4, h * 0.2); g.fill();
  });
  const m = new THREE.Mesh(new THREE.PlaneGeometry(L * 1.18, 2.3), new THREE.MeshBasicMaterial({ color: 0x000000, alphaMap: tex, transparent: true, opacity: 0.85, depthWrite: false }));
  m.rotation.x = -Math.PI / 2; m.position.set(L / 2, 0.002, 0);
  return m;
}

// ---------------------------------------------------------------- render
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1);
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.setClearColor(0x000000, 0);
document.body.appendChild(renderer.domElement);
const env = studioEnv(renderer);

function scene() {
  const s = new THREE.Scene(); s.environment = env;
  const key = new THREE.DirectionalLight(0xffffff, 1.1); key.position.set(2, 8, 4); s.add(key);
  s.add(new THREE.AmbientLight(0xffffff, 0.12));
  return s;
}
function shot(s, cam, w, h) {
  // per-material envMapIntensity only counts when the material has its own envMap
  s.traverse(o => { if (o.isMesh) [].concat(o.material).forEach(m => { if (m.isMeshStandardMaterial && !m.envMap) { m.envMap = env; m.needsUpdate = true; } }); });
  renderer.setSize(w, h, false); renderer.render(s, cam); return renderer.domElement.toDataURL('image/png'); }

export const FRAME = { X0: -0.05, X1: L + 0.05, Y0: -0.02, Y1: 1.50 };
window.renderSide = (paint, opts = {}) => {
  const s = scene(); s.add(carGroup(paint, { ...opts, wheels: !!opts.wheels }));
  const cam = new THREE.OrthographicCamera(FRAME.X0, FRAME.X1, FRAME.Y1, FRAME.Y0, 0.1, 50);
  cam.position.set(0, 0, 20); cam.lookAt(0, 0, 0);
  const w = opts.width || 1400;
  return shot(s, cam, w, Math.round(w * (FRAME.Y1 - FRAME.Y0) / (FRAME.X1 - FRAME.X0)));
};
window.renderWheel = (size = 512) => {
  const s = scene(); s.add(wheelGroup());
  const r = TIRE_R + 0.004;
  const cam = new THREE.OrthographicCamera(-r, r, r, -r, 0.1, 50); cam.position.set(0, 0, 20); cam.lookAt(0, 0, 0);
  return shot(s, cam, size, size);
};
// three-quarter front, slightly above: the "parked in a showroom" picture; mirror = the floor reflection
window.renderParked = (paint, opts = {}) => {
  const s = scene();
  const car = carGroup(paint, { ...opts, wheels: true });
  if (opts.mirror) { car.scale.y = -1; } else s.add(shadowPlane());
  s.add(car);
  const w = opts.width || 1400, h = opts.height || 640;
  const cam = new THREE.PerspectiveCamera(opts.fov || 21, w / h, 0.1, 100);
  const az = (opts.az ?? 38) * Math.PI / 180, el = (opts.el ?? 9) * Math.PI / 180, dist = opts.dist ?? 15.5;
  const tx = L / 2 + 0.15, ty = 0.62;
  cam.position.set(tx + dist * Math.cos(el) * Math.cos(az), ty + dist * Math.sin(el), dist * Math.cos(el) * Math.sin(az));
  cam.lookAt(tx, ty, 0);
  return shot(s, cam, w, h);
};
window.meta = { L, WR, WF, WY, TIRE_R, FRAME };
window.ready = true;
