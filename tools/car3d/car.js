// Original saloon car, built from profile curves and rendered as a side view
// (transparent background) for the Drive Launcher home screen.
import * as THREE from 'three';

const L = 4.86;           // length (m), rear bumper at x = 0, front at x = L
const H = 1.60;           // height of the picture frame (m)
const WR = 0.98, WF = 3.86, WY = 0.345, TIRE_R = 0.345;  // wheel centres and radius
const ARCH_R = 0.378, ARCH_Y = 0.362;

// ---------------------------------------------------------------- curves
function spline(pts) {
  // monotone cubic (Fritsch–Carlson) through [x, y] points
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

const yBottom = spline([[0, 0.42], [0.03, 0.34], [0.12, 0.315], [0.35, 0.30], [0.6, 0.28], [1.4, 0.245], [3.4, 0.245],
  [4.3, 0.26], [4.62, 0.285], [4.76, 0.31], [4.83, 0.34], [L, 0.40]]);
const yBelt = spline([[0, 0.86], [0.025, 0.975], [0.08, 1.025], [0.17, 1.04], [0.5, 1.045], [0.82, 1.05], [1.6, 1.035],
  [2.6, 1.0], [3.3, 0.975], [3.5, 0.962], [4.0, 0.935], [4.45, 0.905], [4.68, 0.878], [4.79, 0.84], [4.845, 0.775], [L, 0.70]]);
const RA = 0.80, RF = 3.36;  // where the rear window and the windshield meet the body
const roofPts = [[RA, 1.05], [0.95, 1.14], [1.2, 1.29], [1.5, 1.39], [1.8, 1.432], [2.05, 1.44], [2.35, 1.428],
  [2.55, 1.40], [2.78, 1.315], [3.0, 1.195], [3.2, 1.065], [RF, 0.974]];
const roofS = spline(roofPts);
const yRoof = x => (x <= RA || x >= RF) ? yBelt(x) : Math.max(yBelt(x), roofS(x));
function halfW(x) {
  let w = 0.928;
  if (x > 4.15) w -= 0.25 * Math.pow((x - 4.15) / (L - 4.15), 2.2);
  if (x < 0.55) w -= 0.17 * Math.pow((0.55 - x) / 0.55, 2.2);
  return w;
}
const smooth = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };

// ---------------------------------------------------------------- body surface
function bodyGeometry() {
  const NX = 260;
  const ring = [];
  const pos = [], uv = [];
  let perRing = 0;
  for (let i = 0; i <= NX; i++) {
    const x = (i / NX) * L;
    const yb = yBottom(x), ybl = yBelt(x), yr = yRoof(x), hw = halfW(x);
    const h = yr - ybl, cab = smooth(0.0, 0.10, h);
    const pts = [];
    // underside edge
    pts.push([yb + 0.01, hw * 0.80]);
    // lower side: tuck-under, full width, shoulder line, roll over to the deck
    const NS = 26;
    for (let k = 0; k <= NS; k++) {
      const s = k / NS;
      let f;
      if (s < 0.42) f = 0.875 + 0.125 * Math.sin((s / 0.42) * Math.PI / 2);
      else if (s < 0.80) f = 1.0 - 0.018 * Math.pow((s - 0.42) / 0.38, 1.3);
      else if (s < 0.835) f = 0.982;
      else f = 0.982 - 0.04 * Math.pow((s - 0.835) / 0.165, 1.5);
      pts.push([yb + s * (ybl - yb), hw * f]);
    }
    // greenhouse side (collapses to the deck edge where there is no cabin)
    const zDeck = hw * 0.968;
    const NG = 14;
    for (let k = 1; k <= NG; k++) {
      const t = k / NG;
      const zc = hw * (0.905 - 0.15 * t);
      pts.push([ybl + t * Math.max(0, h - 0.055 * cab), zDeck + (zc - zDeck) * cab]);
    }
    // rounded roof edge then crown to the centre line
    const zEdge = zDeck + (hw * 0.755 - zDeck) * cab;
    const top = ybl + h;
    const r = 0.055 * cab + 0.03 * (1 - cab);
    const NR = 8;
    const yStart = top - 0.055 * cab;
    for (let k = 1; k <= NR; k++) {
      const a = (k / NR) * Math.PI / 2;
      pts.push([yStart + Math.sin(a) * (top - yStart + 0.012), zEdge - r * (1 - Math.cos(a))]);
    }
    const crown = 0.03 + 0.012 * (1 - cab);
    const NC = 10;
    const yE = yStart + (top - yStart + 0.012), zE = zEdge - r;
    for (let k = 1; k <= NC; k++) {
      const t = k / NC;
      pts.push([yE + crown * (1 - (1 - t) * (1 - t)), zE * (1 - t)]);
    }
    perRing = pts.length;
    for (const [y, z] of pts) { pos.push(x, y, z); uv.push(x / L, y / H); }
  }
  const idx = [];
  for (let i = 0; i < NX; i++) for (let k = 0; k < perRing - 1; k++) {
    const a = i * perRing + k, b = a + 1, c = a + perRing, d = c + 1;
    idx.push(a, c, b, b, c, d);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  g.computeVertexNormals();
  return g;
}

function offsetGeometry(g, d) {
  const o = g.clone();
  const p = o.attributes.position, n = o.attributes.normal;
  for (let i = 0; i < p.count; i++) p.setXYZ(i, p.getX(i) + n.getX(i) * d, p.getY(i) + n.getY(i) * d, p.getZ(i) + n.getZ(i) * d);
  p.needsUpdate = true;
  return o;
}

// ---------------------------------------------------------------- masks (side projection)
const TW = 4096, TH = Math.round(4096 * H / L);
const sx = x => x / L * TW, sy = y => (1 - y / H) * TH, sm = m => m / L * TW;
function mask(draw) {
  const c = document.createElement('canvas'); c.width = TW; c.height = TH;
  const g = c.getContext('2d');
  g.fillStyle = '#000'; g.fillRect(0, 0, TW, TH);
  g.fillStyle = '#fff'; g.strokeStyle = '#fff';
  g.lineJoin = 'round'; g.lineCap = 'round';
  draw(g);
  const t = new THREE.CanvasTexture(c);
  t.flipY = true;
  t.anisotropy = 8;
  return t;
}
function path(g, pts, close = true) {
  g.beginPath();
  pts.forEach(([x, y], i) => i ? g.lineTo(sx(x), sy(y)) : g.moveTo(sx(x), sy(y)));
  if (close) g.closePath();
}
// window opening (day-light opening)
function dlo() {
  const pts = [];
  // bottom (belt) from the front corner to the rear corner
  for (let x = 3.16; x >= 1.08; x -= 0.05) pts.push([x, yBelt(x) + 0.012]);
  // rear quarter window: up along the C pillar
  pts.push([1.02, yBelt(1.02) + 0.012]);
  const back = [];
  for (let x = 1.02; x <= 2.36; x += 0.03) {
    const yr = roofS(Math.min(x + 0.10, RF - 0.01));
    back.push([x, Math.min(roofS(x) - 0.075, yr - 0.07)]);
  }
  for (const p of back) if (p[1] > yBelt(p[0]) + 0.02) pts.push(p);
  // follow the windshield line back down to the front corner
  for (let x = 2.4; x <= 3.20; x += 0.03) {
    const y = roofS(Math.min(x + 0.075, RF - 0.01)) - 0.055;
    if (y > yBelt(x) + 0.02) pts.push([x, y]);
  }
  return pts;
}
const DLO = dlo();
const B_PILLAR = [[2.05, 1.45], [2.14, 1.45], [2.125, 0.95], [2.035, 0.95]];

function paintMask() {
  return mask(g => {
    g.fillRect(0, 0, TW, TH);
    g.fillStyle = '#000';
    for (const cx of [WR, WF]) { g.beginPath(); g.arc(sx(cx), sy(ARCH_Y), sm(ARCH_R), 0, Math.PI * 2); g.fill(); }
  });
}
function glassMask() { return mask(g => { path(g, DLO); g.fill(); g.fillStyle = '#000'; path(g, B_PILLAR); g.fill(); }); }
function trimMask() {
  return mask(g => {
    g.lineWidth = sm(0.05); path(g, DLO); g.stroke();
    g.save(); path(g, DLO); g.clip(); path(g, B_PILLAR); g.fill(); g.restore();
    // rocker panel and lower bumper trims
    g.beginPath();
    for (let x = 0.08; x <= 4.80; x += 0.02) { const y = yBottom(x) + (x > 1.42 && x < 3.42 ? 0.075 : 0.045); x === 0.08 ? g.moveTo(sx(x), sy(y)) : g.lineTo(sx(x), sy(y)); }
    for (let x = 4.80; x >= 0.08; x -= 0.02) g.lineTo(sx(x), sy(yBottom(x) - 0.05));
    g.closePath(); g.fill();
  });
}
function chromeMask() {
  return mask(g => {
    g.lineWidth = sm(0.072); path(g, DLO); g.stroke();
    g.globalCompositeOperation = 'destination-out';
    g.lineWidth = sm(0.05); path(g, DLO); g.stroke(); path(g, DLO); g.fill();
    g.globalCompositeOperation = 'source-over';
    // door handles
    for (const hx of [2.62, 1.50]) {
      g.beginPath(); g.roundRect(sx(hx - 0.095), sy(0.955), sm(0.19), sm(0.034), sm(0.017)); g.fill();
    }
  });
}
function gapMask() {
  return mask(g => {
    g.lineWidth = sm(0.0045);
    // front door leading edge
    path(g, [[3.25, yBelt(3.25) - 0.005], [3.29, 0.80], [3.34, 0.56], [3.35, 0.31]], false); g.stroke();
    // B pillar line (front door rear / rear door front)
    path(g, [[2.09, 0.99], [2.09, 0.28]], false); g.stroke();
    // rear door rear edge, around the rear wheel arch
    g.beginPath(); g.moveTo(sx(1.12), sy(yBelt(1.12) - 0.004)); g.lineTo(sx(1.22), sy(0.76));
    g.arc(sx(WR), sy(ARCH_Y), sm(ARCH_R + 0.08), -Math.acos((1.22 - WR) / (ARCH_R + 0.08)) * 0.999, -0.05, false);
    g.stroke();
    // door bottoms
    path(g, [[3.35, 0.325], [1.44, 0.325]], false); g.stroke();
    // fuel door
    g.beginPath(); g.roundRect(sx(0.52), sy(0.975), sm(0.13), sm(0.12), sm(0.025)); g.stroke();
    // door handle recess outline
    for (const hx of [2.62, 1.50]) { g.beginPath(); g.roundRect(sx(hx - 0.105), sy(0.962), sm(0.21), sm(0.048), sm(0.024)); g.stroke(); }
    // character line shadow below the shoulder
    g.globalAlpha = 0.35; g.lineWidth = sm(0.012);
    g.beginPath();
    for (let x = 0.25; x <= 4.55; x += 0.02) { const y = yBottom(x) + 0.8 * (yBelt(x) - yBottom(x)) - 0.012; x === 0.25 ? g.moveTo(sx(x), sy(y)) : g.lineTo(sx(x), sy(y)); }
    g.stroke(); g.globalAlpha = 1;
  });
}
function headMask() {
  return mask(g => {
    path(g, [[4.835, 0.77], [4.68, 0.835], [4.44, 0.868], [4.40, 0.856], [4.60, 0.815], [4.76, 0.762], [4.83, 0.72]]); g.fill();
  });
}
function drlMask() {
  return mask(g => { g.lineWidth = sm(0.011); path(g, [[4.45, 0.860], [4.66, 0.826], [4.82, 0.758]], false); g.stroke(); });
}
function tailMask() {
  return mask(g => {
    path(g, [[0.02, 0.92], [0.10, 1.012], [0.40, 1.035], [0.44, 1.005], [0.20, 0.975], [0.05, 0.875]]); g.fill();
  });
}
function tailGlowMask() {
  return mask(g => { g.lineWidth = sm(0.012); path(g, [[0.06, 0.94], [0.16, 1.005], [0.40, 1.02]], false); g.stroke(); });
}

// ---------------------------------------------------------------- environment (studio)
function studioEnv(renderer) {
  const scene = new THREE.Scene();
  const geo = new THREE.SphereGeometry(40, 64, 32);
  const col = [];
  const p = geo.attributes.position;
  const sky = new THREE.Color(0.30, 0.32, 0.36), hor = new THREE.Color(0.16, 0.17, 0.19), gnd = new THREE.Color(0.02, 0.022, 0.026);
  for (let i = 0; i < p.count; i++) {
    const y = p.getY(i) / 40;
    const c = y > 0 ? hor.clone().lerp(sky, Math.pow(y, 0.6)) : hor.clone().lerp(gnd, Math.min(1, -y * 6));
    col.push(c.r, c.g, c.b);
  }
  geo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
  scene.add(new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.BackSide })));
  const box = (w, h, x, y, z, k, rx = -Math.PI / 2) => {
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(k, k, k), side: THREE.DoubleSide }));
    m.position.set(x, y, z); m.rotation.x = rx; scene.add(m);
  };
  box(40, 6.0, 2, 15, 7, 4.0);      // wide strip above towards the camera: bright shoulders and roof
  box(10, 10, -6, 18, -4, 3);       // soft boxes overhead
  box(10, 10, 9, 18, -2, 3);
  box(70, 1.3, 0, 2.2, 18, 6, 0);   // low strip in front of the car: crisp line along the upper doors
  box(70, 4, 0, 7.5, 17, 1.8, 0);   // soft wall light: lifts the shoulders
  const pm = new THREE.PMREMGenerator(renderer);
  return pm.fromScene(scene, 0.02).texture;
}

// ---------------------------------------------------------------- wheel
function wheelGroup() {
  const g = new THREE.Group();
  // tyre
  const prof = [];
  const rin = 0.252, rout = TIRE_R, w = 0.245;
  const N = 24;
  for (let i = 0; i <= N; i++) {
    const a = -Math.PI / 2 + (i / N) * Math.PI;
    const r = rout - 0.045 + 0.045 * Math.cos(a) * 1.0;
    const z = (w / 2) * Math.sin(a);
    prof.push(new THREE.Vector2(Math.max(rin, r), z));
  }
  prof.unshift(new THREE.Vector2(rin, -w / 2)); prof.push(new THREE.Vector2(rin, w / 2));
  const tyre = new THREE.Mesh(new THREE.LatheGeometry(prof, 128),
    new THREE.MeshPhysicalMaterial({ color: 0x141414, roughness: 0.82, metalness: 0, sheen: 0.4, sheenColor: 0x444444, sheenRoughness: 0.6 }));
  tyre.rotation.x = Math.PI / 2;
  g.add(tyre);
  // sidewall bulge ring (outer)
  const wall = new THREE.Mesh(new THREE.TorusGeometry(0.298, 0.047, 24, 128),
    new THREE.MeshPhysicalMaterial({ color: 0x161616, roughness: 0.78, metalness: 0 }));
  wall.scale.z = 0.55; wall.position.z = w / 2 - 0.025;
  g.add(wall);
  // barrel and brake
  const barrel = new THREE.Mesh(new THREE.CylinderGeometry(0.238, 0.238, 0.2, 96, 1, true),
    new THREE.MeshStandardMaterial({ color: 0x24272c, metalness: 0.8, roughness: 0.45, side: THREE.DoubleSide }));
  barrel.rotation.x = Math.PI / 2; barrel.position.z = 0.0; g.add(barrel);
  const disc = new THREE.Mesh(new THREE.CylinderGeometry(0.18, 0.18, 0.028, 96),
    new THREE.MeshStandardMaterial({ color: 0x7b8088, metalness: 0.9, roughness: 0.42 }));
  disc.rotation.x = Math.PI / 2; disc.position.z = 0.02; g.add(disc);
  const hat = new THREE.Mesh(new THREE.CylinderGeometry(0.085, 0.085, 0.03, 64),
    new THREE.MeshStandardMaterial({ color: 0x3a3e44, metalness: 0.7, roughness: 0.5 }));
  hat.rotation.x = Math.PI / 2; hat.position.z = 0.035; g.add(hat);
  const caliper = new THREE.Mesh(new THREE.TorusGeometry(0.15, 0.032, 16, 32, 0.9),
    new THREE.MeshStandardMaterial({ color: 0x2f3236, metalness: 0.5, roughness: 0.45 }));
  caliper.rotation.z = Math.PI * 0.62; caliper.position.z = 0.05; caliper.scale.z = 1.4; g.add(caliper);
  // rim face: 5 double spokes
  const face = new THREE.Shape();
  face.absarc(0, 0, 0.236, 0, Math.PI * 2, false);
  const holes = [];
  const spokes = 5;
  for (let s = 0; s < spokes; s++) {
    const base = (s / spokes) * Math.PI * 2 + Math.PI / 2;
    // two windows per sector: a thin one between the pair, a wide one between pairs
    const win = (a0, a1, r0, r1) => {
      const hsh = new THREE.Path();
      hsh.absarc(0, 0, r1, a0, a1, false);
      hsh.absarc(0, 0, r0, a1 - 0.02, a0 + 0.02, true);
      hsh.closePath();
      holes.push(hsh);
    };
    win(base + 0.12, base + (Math.PI * 2 / spokes) - 0.12, 0.085, 0.214);
    win(base - 0.04, base + 0.04, 0.122, 0.208);
  }
  face.holes = holes;
  const rimGeo = new THREE.ExtrudeGeometry(face, { depth: 0.028, bevelEnabled: true, bevelThickness: 0.008, bevelSize: 0.006, bevelSegments: 4, curveSegments: 64 });
  const rimMat = new THREE.MeshPhysicalMaterial({ color: 0xc9ced6, metalness: 1.0, roughness: 0.22, clearcoat: 0.6, clearcoatRoughness: 0.1 });
  const rim = new THREE.Mesh(rimGeo, rimMat);
  rim.position.z = 0.085; g.add(rim);
  // dished lip ring
  const lip = new THREE.Mesh(new THREE.TorusGeometry(0.236, 0.008, 12, 128), rimMat);
  lip.position.z = 0.118; g.add(lip);
  // hub cap and nuts
  const cap = new THREE.Mesh(new THREE.CylinderGeometry(0.038, 0.042, 0.02, 48),
    new THREE.MeshPhysicalMaterial({ color: 0x15171a, metalness: 0.4, roughness: 0.2, clearcoat: 1 }));
  cap.rotation.x = Math.PI / 2; cap.position.z = 0.122; g.add(cap);
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * Math.PI * 2 + Math.PI / 2 + Math.PI / 5;
    const nut = new THREE.Mesh(new THREE.CylinderGeometry(0.0095, 0.0095, 0.018, 6), rimMat);
    nut.rotation.x = Math.PI / 2; nut.position.set(Math.cos(a) * 0.062, Math.sin(a) * 0.062, 0.118); g.add(nut);
  }
  return g;
}

// ---------------------------------------------------------------- scene
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1);
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.setClearColor(0x000000, 0);
document.body.appendChild(renderer.domElement);
const env = studioEnv(renderer);

const masks = {};
function getMask(k, f) { return masks[k] || (masks[k] = f()); }

function carScene(paint, opts) {
  const scene = new THREE.Scene();
  scene.environment = env;
  const key = new THREE.DirectionalLight(0xffffff, 1.2); key.position.set(1, 6, 4); scene.add(key);
  scene.add(new THREE.AmbientLight(0xffffff, 0.15));
  const body = bodyGeometry();
  const paintMat = new THREE.MeshPhysicalMaterial({
    color: new THREE.Color(paint.color), metalness: paint.metal, roughness: paint.rough,
    clearcoat: 1.0, clearcoatRoughness: 0.035, alphaMap: getMask('paint', paintMask), alphaTest: 0.5, envMapIntensity: paint.env ?? 1.0,
  });
  scene.add(new THREE.Mesh(body, paintMat));
  const layer = (d, mat, m) => {
    mat.alphaMap = m; mat.transparent = true; mat.depthWrite = false;
    const mesh = new THREE.Mesh(offsetGeometry(body, d), mat); mesh.renderOrder = Math.round(d * 10000); scene.add(mesh);
  };
  layer(0.0012, new THREE.MeshPhysicalMaterial({ color: 0x050607, roughness: 0.65, metalness: 0 }), getMask('gap', gapMask));
  layer(0.0020, new THREE.MeshPhysicalMaterial({ color: 0x0b0c0e, roughness: 0.3, metalness: 0.1, clearcoat: 0.6 }), getMask('trim', trimMask));
  layer(0.0028, new THREE.MeshPhysicalMaterial({ color: 0xd8dde3, roughness: 0.12, metalness: 1.0 }), getMask('chrome', chromeMask));
  layer(0.0036, new THREE.MeshPhysicalMaterial({ color: 0x0a0e13, roughness: 0.03, metalness: 0.0, clearcoat: 1, envMapIntensity: 1.4 }), getMask('glass', glassMask));
  layer(0.0036, new THREE.MeshPhysicalMaterial({ color: 0x15181d, roughness: 0.08, metalness: 0.6, clearcoat: 1 }), getMask('head', headMask));
  layer(0.0044, new THREE.MeshBasicMaterial({ color: new THREE.Color(opts.drl || 0xeaf4ff) }), getMask('drl', drlMask));
  layer(0.0036, new THREE.MeshPhysicalMaterial({ color: 0x3a0606, roughness: 0.08, metalness: 0.2, clearcoat: 1 }), getMask('tail', tailMask));
  layer(0.0044, new THREE.MeshBasicMaterial({ color: new THREE.Color(1.0, 0.13, 0.16) }), getMask('tailg', tailGlowMask));
  // wheel wells
  for (const cx of [WR, WF]) {
    const well = new THREE.Mesh(new THREE.CircleGeometry(ARCH_R + 0.01, 64), new THREE.MeshStandardMaterial({ color: 0x050506, roughness: 0.9 }));
    well.position.set(cx, ARCH_Y, halfW(cx) * 0.55); scene.add(well);
  }
  // side mirror
  const mir = new THREE.Mesh(new THREE.SphereGeometry(1, 48, 24), paintMat.clone());
  mir.material.alphaMap = null; mir.material.alphaTest = 0;
  mir.scale.set(0.10, 0.052, 0.075); mir.position.set(3.06, 1.06, halfW(3.0) * 0.93 + 0.10); mir.rotation.z = 0.12; scene.add(mir);
  const stem = new THREE.Mesh(new THREE.BoxGeometry(0.07, 0.035, 0.12), new THREE.MeshStandardMaterial({ color: 0x0b0c0e, roughness: 0.4 }));
  stem.position.set(3.12, 1.015, halfW(3.0) * 0.93 + 0.045); scene.add(stem);
  if (opts.wheels) for (const cx of [WR, WF]) { const w = wheelGroup(); w.position.set(cx, WY, halfW(cx) - 0.135); scene.add(w); }
  return scene;
}

function render(scene, camera, w, h) {
  renderer.setSize(w, h, false);
  renderer.render(scene, camera);
  return renderer.domElement.toDataURL('image/png');
}

const X0 = -0.04, X1 = L + 0.04, Y0 = -0.02, Y1 = 1.56;
window.renderCar = (paint, opts = {}) => {
  const scene = carScene(paint, opts);
  const cam = new THREE.OrthographicCamera(X0, X1, Y1, Y0, 0.1, 50);
  cam.position.set(0, 0, 20); cam.lookAt(0, 0, 0);
  const w = opts.width || 2800;
  return render(scene, cam, w, Math.round(w * (Y1 - Y0) / (X1 - X0)));
};
window.renderWheel = (size = 512) => {
  const scene = new THREE.Scene();
  scene.environment = env;
  const key = new THREE.DirectionalLight(0xffffff, 1.2); key.position.set(1, 6, 4); scene.add(key);
  scene.add(new THREE.AmbientLight(0xffffff, 0.15));
  scene.add(wheelGroup());
  const r = TIRE_R + 0.004;
  const cam = new THREE.OrthographicCamera(-r, r, r, -r, 0.1, 50);
  cam.position.set(0, 0, 20); cam.lookAt(0, 0, 0);
  return render(scene, cam, size, size);
};
window.meta = { L, X0, X1, Y0, Y1, WR, WF, WY, TIRE_R, head: [4.80, 0.70], tail: [0.08, 0.95] };
window.ready = true;
