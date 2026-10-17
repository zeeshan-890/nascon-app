/**
 * Maximum-weight matching in general graphs (Edmonds blossom algorithm, O(n^3)),
 * following the primal-dual formulation of Galil / van Rantwijk. Hand-written, no dependencies.
 *
 * edges: array of [i, j, weight] with integer weights. Returns `mate` where mate[v] is the
 * partner of v or -1. With maxCardinality the result is a maximum-cardinality matching that
 * has maximum weight among those.
 */
function maxWeightMatching(edges, maxCardinality = false, vertexCount = 0) {
  let nvertex = vertexCount;
  for (const [i, j] of edges) nvertex = Math.max(nvertex, i + 1, j + 1);
  if (edges.length === 0) return new Array(nvertex).fill(-1);

  const nedge = edges.length;
  let maxweight = 0;
  for (const e of edges) maxweight = Math.max(maxweight, e[2]);

  const endpoint = new Array(2 * nedge);
  for (let p = 0; p < 2 * nedge; p += 1) endpoint[p] = edges[p >> 1][p & 1];
  const neighbend = Array.from({ length: nvertex }, () => []);
  edges.forEach(([i, j], k) => {
    neighbend[i].push(2 * k + 1);
    neighbend[j].push(2 * k);
  });

  const mate = new Array(nvertex).fill(-1);
  const label = new Array(2 * nvertex).fill(0);
  const labelend = new Array(2 * nvertex).fill(-1);
  const inblossom = Array.from({ length: nvertex }, (_, i) => i);
  const blossomparent = new Array(2 * nvertex).fill(-1);
  const blossomchilds = new Array(2 * nvertex).fill(null);
  const blossombase = Array.from({ length: nvertex }, (_, i) => i).concat(new Array(nvertex).fill(-1));
  const blossomendps = new Array(2 * nvertex).fill(null);
  const bestedge = new Array(2 * nvertex).fill(-1);
  const blossombestedges = new Array(2 * nvertex).fill(null);
  const unusedblossoms = [];
  for (let b = nvertex; b < 2 * nvertex; b += 1) unusedblossoms.push(b);
  const dualvar = new Array(nvertex).fill(maxweight).concat(new Array(nvertex).fill(0));
  const allowedge = new Array(nedge).fill(false);
  let queue = [];

  // Python-style indexing that tolerates negative offsets into a cyclic list.
  const at = (arr, j) => arr[((j % arr.length) + arr.length) % arr.length];

  const slack = (k) => {
    const [i, j, wt] = edges[k];
    return dualvar[i] + dualvar[j] - 2 * wt;
  };

  function blossomLeaves(b) {
    if (b < nvertex) return [b];
    const out = [];
    const stack = [b];
    while (stack.length) {
      const t = stack.pop();
      if (t < nvertex) out.push(t);
      else for (const c of blossomchilds[t]) stack.push(c);
    }
    return out;
  }

  function assignLabel(w, t, p) {
    const b = inblossom[w];
    label[w] = t;
    label[b] = t;
    labelend[w] = p;
    labelend[b] = p;
    bestedge[w] = -1;
    bestedge[b] = -1;
    if (t === 1) {
      for (const v of blossomLeaves(b)) queue.push(v);
    } else if (t === 2) {
      const base = blossombase[b];
      assignLabel(endpoint[mate[base]], 1, mate[base] ^ 1);
    }
  }

  function scanBlossom(v0, w0) {
    let v = v0;
    let w = w0;
    const path = [];
    let base = -1;
    while (v !== -1 || w !== -1) {
      let b = inblossom[v];
      if (label[b] & 4) {
        base = blossombase[b];
        break;
      }
      path.push(b);
      label[b] = 5;
      if (labelend[b] === -1) {
        v = -1;
      } else {
        v = endpoint[labelend[b]];
        b = inblossom[v];
        v = endpoint[labelend[b]];
      }
      if (w !== -1) {
        const tmp = v;
        v = w;
        w = tmp;
      }
    }
    for (const b of path) label[b] = 1;
    return base;
  }

  function addBlossom(base, k) {
    let [v, w] = edges[k];
    const bb = inblossom[base];
    let bv = inblossom[v];
    let bw = inblossom[w];
    const b = unusedblossoms.pop();
    blossombase[b] = base;
    blossomparent[b] = -1;
    blossomparent[bb] = b;
    const path = [];
    const endps = [];
    blossomchilds[b] = path;
    blossomendps[b] = endps;
    while (bv !== bb) {
      blossomparent[bv] = b;
      path.push(bv);
      endps.push(labelend[bv]);
      v = endpoint[labelend[bv]];
      bv = inblossom[v];
    }
    path.push(bb);
    path.reverse();
    endps.reverse();
    endps.push(2 * k);
    while (bw !== bb) {
      blossomparent[bw] = b;
      path.push(bw);
      endps.push(labelend[bw] ^ 1);
      w = endpoint[labelend[bw]];
      bw = inblossom[w];
    }
    label[b] = 1;
    labelend[b] = labelend[bb];
    dualvar[b] = 0;
    for (const leaf of blossomLeaves(b)) {
      if (label[inblossom[leaf]] === 2) queue.push(leaf);
      inblossom[leaf] = b;
    }
    const bestedgeto = new Array(2 * nvertex).fill(-1);
    for (const sub of path) {
      let nblists;
      if (blossombestedges[sub] === null) {
        nblists = blossomLeaves(sub).map((leaf) => neighbend[leaf].map((p) => p >> 1));
      } else {
        nblists = [blossombestedges[sub]];
      }
      for (const nblist of nblists) {
        for (const kk of nblist) {
          let [i, j] = edges[kk];
          if (inblossom[j] === b) {
            const tmp = i;
            i = j;
            j = tmp;
          }
          const bj = inblossom[j];
          if (bj !== b && label[bj] === 1
            && (bestedgeto[bj] === -1 || slack(kk) < slack(bestedgeto[bj]))) {
            bestedgeto[bj] = kk;
          }
        }
      }
      blossombestedges[sub] = null;
      bestedge[sub] = -1;
    }
    blossombestedges[b] = bestedgeto.filter((kk) => kk !== -1);
    bestedge[b] = -1;
    for (const kk of blossombestedges[b]) {
      if (bestedge[b] === -1 || slack(kk) < slack(bestedge[b])) bestedge[b] = kk;
    }
  }

  function expandBlossom(b, endstage) {
    for (const s of blossomchilds[b]) {
      blossomparent[s] = -1;
      if (s < nvertex) {
        inblossom[s] = s;
      } else if (endstage && dualvar[s] === 0) {
        expandBlossom(s, endstage);
      } else {
        for (const leaf of blossomLeaves(s)) inblossom[leaf] = s;
      }
    }
    if (!endstage && label[b] === 2) {
      const childs = blossomchilds[b];
      const endps = blossomendps[b];
      const entrychild = inblossom[endpoint[labelend[b] ^ 1]];
      let j = childs.indexOf(entrychild);
      let jstep;
      let endptrick;
      if (j & 1) {
        j -= childs.length;
        jstep = 1;
        endptrick = 0;
      } else {
        jstep = -1;
        endptrick = 1;
      }
      let p = labelend[b];
      while (j !== 0) {
        label[endpoint[p ^ 1]] = 0;
        label[endpoint[at(endps, j - endptrick) ^ endptrick ^ 1]] = 0;
        assignLabel(endpoint[p ^ 1], 2, p);
        allowedge[at(endps, j - endptrick) >> 1] = true;
        j += jstep;
        p = at(endps, j - endptrick) ^ endptrick;
        allowedge[p >> 1] = true;
        j += jstep;
      }
      let bv = at(childs, j);
      label[endpoint[p ^ 1]] = 2;
      label[bv] = 2;
      labelend[endpoint[p ^ 1]] = p;
      labelend[bv] = p;
      bestedge[bv] = -1;
      j += jstep;
      while (at(childs, j) !== entrychild) {
        bv = at(childs, j);
        if (label[bv] === 1) {
          j += jstep;
          continue;
        }
        let found = -1;
        for (const leaf of blossomLeaves(bv)) {
          if (label[leaf] !== 0) {
            found = leaf;
            break;
          }
        }
        if (found !== -1) {
          label[found] = 0;
          label[endpoint[mate[blossombase[bv]]]] = 0;
          assignLabel(found, 2, labelend[found]);
        }
        j += jstep;
      }
    }
    label[b] = -1;
    labelend[b] = -1;
    blossomchilds[b] = null;
    blossomendps[b] = null;
    blossombase[b] = -1;
    blossombestedges[b] = null;
    bestedge[b] = -1;
    unusedblossoms.push(b);
  }

  function augmentBlossom(b, v) {
    let t = v;
    while (blossomparent[t] !== b) t = blossomparent[t];
    if (t >= nvertex) augmentBlossom(t, v);
    const childs = blossomchilds[b];
    const endps = blossomendps[b];
    const i = childs.indexOf(t);
    let j = i;
    let jstep;
    let endptrick;
    if (i & 1) {
      j -= childs.length;
      jstep = 1;
      endptrick = 0;
    } else {
      jstep = -1;
      endptrick = 1;
    }
    while (j !== 0) {
      j += jstep;
      t = at(childs, j);
      const p = at(endps, j - endptrick) ^ endptrick;
      if (t >= nvertex) augmentBlossom(t, endpoint[p]);
      j += jstep;
      t = at(childs, j);
      if (t >= nvertex) augmentBlossom(t, endpoint[p ^ 1]);
      mate[endpoint[p]] = p ^ 1;
      mate[endpoint[p ^ 1]] = p;
    }
    blossomchilds[b] = childs.slice(i).concat(childs.slice(0, i));
    blossomendps[b] = endps.slice(i).concat(endps.slice(0, i));
    blossombase[b] = blossombase[blossomchilds[b][0]];
  }

  function augmentMatching(k) {
    const [v, w] = edges[k];
    for (const [s0, p0] of [[v, 2 * k + 1], [w, 2 * k]]) {
      let s = s0;
      let p = p0;
      for (;;) {
        const bs = inblossom[s];
        if (bs >= nvertex) augmentBlossom(bs, s);
        mate[s] = p;
        if (labelend[bs] === -1) break;
        const t = endpoint[labelend[bs]];
        const bt = inblossom[t];
        s = endpoint[labelend[bt]];
        const j = endpoint[labelend[bt] ^ 1];
        if (bt >= nvertex) augmentBlossom(bt, j);
        mate[j] = labelend[bt];
        p = labelend[bt] ^ 1;
      }
    }
  }

  for (let stage = 0; stage < nvertex; stage += 1) {
    label.fill(0);
    bestedge.fill(-1);
    for (let b = nvertex; b < 2 * nvertex; b += 1) blossombestedges[b] = null;
    allowedge.fill(false);
    queue = [];
    for (let v = 0; v < nvertex; v += 1) {
      if (mate[v] === -1 && label[inblossom[v]] === 0) assignLabel(v, 1, -1);
    }
    let augmented = false;
    for (;;) {
      while (queue.length && !augmented) {
        const v = queue.pop();
        for (const p of neighbend[v]) {
          const k = p >> 1;
          const w = endpoint[p];
          if (inblossom[v] === inblossom[w]) continue;
          let kslack = 0;
          if (!allowedge[k]) {
            kslack = slack(k);
            if (kslack <= 0) allowedge[k] = true;
          }
          if (allowedge[k]) {
            if (label[inblossom[w]] === 0) {
              assignLabel(w, 2, p ^ 1);
            } else if (label[inblossom[w]] === 1) {
              const base = scanBlossom(v, w);
              if (base >= 0) {
                addBlossom(base, k);
              } else {
                augmentMatching(k);
                augmented = true;
                break;
              }
            } else if (label[w] === 0) {
              label[w] = 2;
              labelend[w] = p ^ 1;
            }
          } else if (label[inblossom[w]] === 1) {
            const b = inblossom[v];
            if (bestedge[b] === -1 || kslack < slack(bestedge[b])) bestedge[b] = k;
          } else if (label[w] === 0) {
            if (bestedge[w] === -1 || kslack < slack(bestedge[w])) bestedge[w] = k;
          }
        }
      }
      if (augmented) break;

      let deltatype = -1;
      let delta = 0;
      let deltaedge = -1;
      let deltablossom = -1;
      if (!maxCardinality) {
        deltatype = 1;
        delta = Math.min(...dualvar.slice(0, nvertex));
      }
      for (let v = 0; v < nvertex; v += 1) {
        if (label[inblossom[v]] === 0 && bestedge[v] !== -1) {
          const d = slack(bestedge[v]);
          if (deltatype === -1 || d < delta) {
            delta = d;
            deltatype = 2;
            deltaedge = bestedge[v];
          }
        }
      }
      for (let b = 0; b < 2 * nvertex; b += 1) {
        if (blossomparent[b] === -1 && label[b] === 1 && bestedge[b] !== -1) {
          const d = Math.floor(slack(bestedge[b]) / 2);
          if (deltatype === -1 || d < delta) {
            delta = d;
            deltatype = 3;
            deltaedge = bestedge[b];
          }
        }
      }
      for (let b = nvertex; b < 2 * nvertex; b += 1) {
        if (blossombase[b] >= 0 && blossomparent[b] === -1 && label[b] === 2
          && (deltatype === -1 || dualvar[b] < delta)) {
          delta = dualvar[b];
          deltatype = 4;
          deltablossom = b;
        }
      }
      if (deltatype === -1) {
        deltatype = 1;
        delta = Math.max(0, Math.min(...dualvar.slice(0, nvertex)));
      }
      for (let v = 0; v < nvertex; v += 1) {
        if (label[inblossom[v]] === 1) dualvar[v] -= delta;
        else if (label[inblossom[v]] === 2) dualvar[v] += delta;
      }
      for (let b = nvertex; b < 2 * nvertex; b += 1) {
        if (blossombase[b] >= 0 && blossomparent[b] === -1) {
          if (label[b] === 1) dualvar[b] += delta;
          else if (label[b] === 2) dualvar[b] -= delta;
        }
      }
      if (deltatype === 1) {
        break;
      } else if (deltatype === 2) {
        allowedge[deltaedge] = true;
        let [i, j] = edges[deltaedge];
        if (label[inblossom[i]] === 0) {
          const tmp = i;
          i = j;
          j = tmp;
        }
        queue.push(i);
      } else if (deltatype === 3) {
        allowedge[deltaedge] = true;
        queue.push(edges[deltaedge][0]);
      } else {
        expandBlossom(deltablossom, false);
      }
    }
    if (!augmented) break;
    for (let b = nvertex; b < 2 * nvertex; b += 1) {
      if (blossomparent[b] === -1 && blossombase[b] >= 0 && label[b] === 1 && dualvar[b] === 0) {
        expandBlossom(b, true);
      }
    }
  }

  for (let v = 0; v < nvertex; v += 1) {
    if (mate[v] >= 0) mate[v] = endpoint[mate[v]];
  }
  return mate;
}

/**
 * Minimum-total-cost perfect matching on `n` vertices. edges: [u, v, cost] with integer cost >= 0.
 * Returns an array of [u, v] pairs, or null when no perfect matching exists.
 */
function minCostPerfectMatching(n, edges) {
  if (n % 2 === 1) return null;
  if (n === 0) return [];
  if (edges.length < n / 2) return null;
  let maxD = 0;
  for (const e of edges) maxD = Math.max(maxD, e[2]);
  const big = maxD + 1;
  const mate = maxWeightMatching(edges.map(([u, v, d]) => [u, v, big - d]), true, n);
  const pairs = [];
  for (let v = 0; v < n; v += 1) {
    if (mate[v] === -1) return null;
    if (v < mate[v]) pairs.push([v, mate[v]]);
  }
  return pairs;
}

module.exports = { maxWeightMatching, minCostPerfectMatching };
