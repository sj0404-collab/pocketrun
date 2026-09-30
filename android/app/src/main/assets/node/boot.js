/*
 * PocketRun Node-compatible layer.
 *
 * Rhino gives us an ES6 interpreter with no Node APIs; this file supplies the
 * subset of Node that pure-JS npm packages rely on: module system (require /
 * module.exports / __dirname), process, console, Buffer, path, fs, os, events,
 * url, http(s), querystring, assert, timers and a Promise polyfill.
 *
 * Everything that touches the outside world goes through the `__pr` bridge
 * installed by JsRuntime.kt: filesystem calls are sandboxed to the workspace
 * root, and the Kotlin side drives the timer loop after the main script ends.
 *
 * Loaded as the very first script into a fresh, safe Rhino scope.
 */
(function (global) {
  'use strict';

  var bridge = global.__pr;
  var ROOT = bridge.root();

  function err(msg) { return new Error(msg); }
  function enoent(op, p) { return err("ENOENT: no such file or directory, " + op + " '" + p + "'"); }
  function isString(x) { return typeof x === 'string'; }

  // ---------------------------------------------------------------- timers

  var timers = new Map();
  var immediates = [];
  var ticks = [];
  var nextTimerId = 1;
  var exited = false;
  var exitHandlers = [];

  function schedule(fn, ms, args, interval) {
    var id = nextTimerId++;
    var delay = Math.max(0, Number(ms) || 0);
    timers.set(id, { due: bridge.now() + delay, interval: interval ? Math.max(1, Number(ms) || 1) : 0, fn: fn, args: args });
    return id;
  }
  function setTimeout(fn, ms) { return schedule(fn, ms, Array.prototype.slice.call(arguments, 2), false); }
  function setInterval(fn, ms) { return schedule(fn, ms, Array.prototype.slice.call(arguments, 2), true); }
  function clearTimeout(id) { timers.delete(id); }
  var clearInterval = clearTimeout;
  function setImmediate(fn) { immediates.push({ fn: fn, args: Array.prototype.slice.call(arguments, 1) }); }
  function clearImmediate() {}

  function drainMicrotasks() {
    while (ticks.length || immediates.length) {
      while (ticks.length) { var t = ticks.shift(); try { t(); } catch (e) { bridge.printErr('Uncaught in nextTick: ' + (e && e.stack ? e.stack : e) + '\n'); } }
      while (immediates.length) { var m = immediates.shift(); try { m.fn.apply(null, m.args); } catch (e) { bridge.printErr('Uncaught in setImmediate: ' + (e && e.stack ? e.stack : e) + '\n'); } }
    }
  }

  // Hooks the Kotlin event loop calls after the main script returns.
  global.__prRunDue = function () {
    drainMicrotasks();
    var now = bridge.now();
    // Collect, then execute in due-time order: a delayed OS wakeup can make
    // several timers due in the same pass, and Node would still run the
    // earliest-scheduled one first.
    var due = [];
    timers.forEach(function (t, id) { if (t.due <= now) { due.push({ id: id, t: t }); } });
    due.sort(function (a, b) { return (a.t.due - b.t.due) || (a.id - b.id); });
    for (var i = 0; i < due.length; i++) {
      var d = due[i];
      if (!timers.has(d.id)) { continue; }
      if (d.t.interval) { d.t.due = now + d.t.interval; } else { timers.delete(d.id); }
      try { d.t.fn.apply(null, d.t.args); }
      catch (e) {
        bridge.printErr('Uncaught exception in timer callback: ' + (e && e.stack ? e.stack : e) + '\n');
      }
    }
    drainMicrotasks();
  };
  global.__prPending = function () { return timers.size + immediates.length + ticks.length; };
  global.__prNextDue = function () {
    var min = -1;
    timers.forEach(function (t) { if (min < 0 || t.due < min) { min = t.due; } });
    return min;
  };
  global.__prExitCode = function () { return exited ? global.__processExitCode : null; };
  global.__prOnExit = function () {
    var code = global.__processExitCode;
    if (code === undefined || code === null) { code = 0; }
    for (var i = 0; i < exitHandlers.length; i++) { exitHandlers[i](code); }
  };

  // ---------------------------------------------------------------- process

  var argv = bridge.argv();
  var envPairs = bridge.env();
  var env = {};
  for (var i = 0; i < envPairs.length; i++) {
    var eq = envPairs[i].indexOf('=');
    if (eq > 0) { env[envPairs[i].substring(0, eq)] = envPairs[i].substring(eq + 1); }
  }

  function Writable(stream) {
    this.write = function (s) { if (stream === 'err') { bridge.printErr(s); } else { bridge.print(s); } return true; };
    this.end = function (s) { if (s) { this.write(s); } };
    this.on = function () { return this; };
    this.once = function () { return this; };
    this.emit = function () { return false; };
  }

  var process = {
    argv: argv,
    env: env,
    platform: 'android',
    arch: 'arm64',
    title: 'node',
    pid: 1,
    version: 'v18.16.0',
    versions: { node: '18.16.0', pocketrun: '1.0' },
    execPath: '/usr/local/bin/node',
    stdout: new Writable('out'),
    stderr: new Writable('err'),
    exit: function (code) {
      global.__processExitCode = (code === undefined || code === null) ? 0 : Number(code) || 0;
      exited = true;
    },
    cwd: function () { return bridge.cwd(); },
    chdir: function (d) { if (!bridge.chdir(d)) { throw err("ENOENT: no such file or directory, chdir '" + d + "'"); } },
    nextTick: function (fn) { ticks.push(fn); },
    on: function (ev, fn) { if (ev === 'exit') { exitHandlers.push(fn); } return process; },
    once: function (ev, fn) { return process.on(ev, fn); },
    removeAllListeners: function () { return process; },
    hrtime: function () { return [Math.floor(bridge.now() / 1000), (bridge.now() % 1000) * 1e6]; },
    memoryUsage: function () { return { rss: 0, heapTotal: 0, heapUsed: 0, external: 0 }; },
    uptime: function () { return 0; },
  };

  // ---------------------------------------------------------------- util

  function inspectValue(v, depth, seen) {
    if (v === null) { return 'null'; }
    if (v === undefined) { return 'undefined'; }
    switch (typeof v) {
      case 'string': return "'" + v + "'";
      case 'number': case 'boolean': return String(v);
      case 'function': return '[Function' + (v.name ? ': ' + v.name : '') + ']';
      case 'symbol': return String(v);
    }
    if (seen.indexOf(v) >= 0) { return '[Circular]'; }
    if (depth <= 0) { return Array.isArray(v) ? '[Array]' : '[Object]'; }
    seen = seen.concat([v]);
    if (Array.isArray(v)) {
      if (v.length === 0) { return '[]'; }
      var items = v.slice(0, 100).map(function (x) { return inspectValue(x, depth - 1, seen); });
      return '[ ' + items.join(', ') + (v.length > 100 ? ', … ' + v.length + ' items' : '') + ' ]';
    }
    if (v instanceof Error) { return v.stack || (v.name + ': ' + v.message); }
    if (v instanceof Date) { return v.toISOString(); }
    var keys = Object.keys(v);
    if (keys.length === 0) { return '{}'; }
    var body = keys.slice(0, 100).map(function (k) { return k + ': ' + inspectValue(v[k], depth - 1, seen); });
    if (keys.length > 100) { body.push('… ' + keys.length + ' keys'); }
    return '{ ' + body.join(', ') + ' }';
  }

  function format(f) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (!isString(f)) { return [f].concat(args).map(function (x) { return isString(x) ? x : inspectValue(x, 4, []); }).join(' '); }
    var i = 0, out = '';
    var str = f.replace(/%[sdfoOj%]/g, function (m) {
      if (m === '%%') { return '%'; }
      if (i >= args.length) { return m; }
      var a = args[i++];
      switch (m) {
        case '%d': case '%f': return String(Number(a));
        case '%j': try { return JSON.stringify(a); } catch (e) { return '[Circular]'; }
        case '%o': case '%O': case '%s':
        default: return isString(a) ? a : inspectValue(a, 4, []);
      }
    });
    for (; i < args.length; i++) { str += ' ' + (isString(args[i]) ? args[i] : inspectValue(args[i], 4, [])); }
    return str;
  }

  var util = {
    format: format,
    inspect: function (v, opts) { return inspectValue(v, (opts && opts.depth !== undefined) ? opts.depth : 4, []); },
    types: {
      isDate: function (x) { return x instanceof Date; },
      isRegExp: function (x) { return x instanceof RegExp; },
      isFunction: function (x) { return typeof x === 'function'; },
      isPrimitive: function (x) { return x === null || (typeof x !== 'object' && typeof x !== 'function'); },
    },
    inherits: function (ctor, superCtor) {
      ctor.super_ = superCtor;
      ctor.prototype = Object.create(superCtor.prototype, { constructor: { value: ctor, enumerable: false } });
    },
    callbackify: function () {},
    promisify: function (fn) {
      return function () {
        var a = Array.prototype.slice.call(arguments);
        return new Promise(function (resolve, reject) {
          a.push(function (e, r) { if (e) { reject(e); } else { resolve(r); } });
          fn.apply(null, a);
        });
      };
    },
  };

  // ---------------------------------------------------------------- console

  function consoleLine(stream) {
    return function () {
      var line = format.apply(null, arguments);
      if (stream === 'err') { bridge.printErr(line + '\n'); } else { bridge.print(line + '\n'); }
    };
  }
  var console = {
    log: consoleLine('out'),
    info: consoleLine('out'),
    debug: consoleLine('out'),
    warn: consoleLine('err'),
    error: consoleLine('err'),
    trace: function () { bridge.print((new Error('Trace')).stack + '\n'); },
    dir: function (v) { bridge.print(inspectValue(v, 4, []) + '\n'); },
    time: function () {}, timeEnd: function () {},
  };

  // ---------------------------------------------------------------- path (posix)

  var path = (function () {
    function normalize(p) {
      if (!isString(p)) { throw err('path must be a string'); }
      if (p === '') { return '.'; }
      var isAbs = p.charAt(0) === '/';
      var parts = p.split('/');
      var out = [];
      for (var i = 0; i < parts.length; i++) {
        var seg = parts[i];
        if (seg === '' || seg === '.') { continue; }
        if (seg === '..') {
          if (out.length > 0 && out[out.length - 1] !== '..') { out.pop(); }
          else if (!isAbs) { out.push('..'); }
        } else { out.push(seg); }
      }
      var r = out.join('/');
      if (isAbs) { r = '/' + r; }
      if (r === '') { return isAbs ? '/' : '.'; }
      return r;
    }

    function join() {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        if (isString(arguments[i]) && arguments[i] !== '') { parts.push(arguments[i]); }
      }
      if (parts.length === 0) { return '.'; }
      return normalize(parts.join('/'));
    }

    function resolve() {
      var paths = [];
      for (var i = 0; i < arguments.length; i++) {
        if (arguments[i] !== undefined && arguments[i] !== null && String(arguments[i]) !== '') { paths.push(String(arguments[i])); }
      }
      var resolved = null;
      for (var j = paths.length - 1; j >= 0; j--) {
        var p = paths[j];
        resolved = resolved === null ? p : p + '/' + resolved;
        if (p.charAt(0) === '/') { break; }
      }
      if (resolved === null) { return normalize(bridge.cwd()); }
      if (resolved.charAt(0) !== '/') { resolved = bridge.cwd() + '/' + resolved; }
      return normalize(resolved);
    }

    function isAbsolute(p) { return isString(p) && p.charAt(0) === '/'; }

    function relative(from, to) {
      from = resolve(from);
      to = resolve(to);
      if (from === to) { return ''; }
      var fa = from.split('/');
      var ta = to.split('/');
      var i = 0;
      while (i < fa.length && i < ta.length && fa[i] === ta[i]) { i++; }
      var ups = fa.length - i;
      var rel = [];
      for (var j = 0; j < ups; j++) { rel.push('..'); }
      for (var k = i; k < ta.length; k++) { rel.push(ta[k]); }
      return rel.length ? rel.join('/') : '.';
    }

    function dirname(p) {
      if (!isString(p)) { throw err('path must be a string'); }
      if (p === '') { return '.'; }
      var np = normalize(p);
      var idx = np.lastIndexOf('/');
      if (idx < 0) { return '.'; }
      if (idx === 0) { return '/'; }
      return np.substring(0, idx);
    }

    function basename(p, ext) {
      if (!isString(p)) { throw err('path must be a string'); }
      var np = normalize(p);
      if (np === '/') { return '/'; }
      var segs = np.split('/');
      var b = segs[segs.length - 1];
      if (b === '') { b = segs.length > 1 ? segs[segs.length - 2] : ''; }
      if (ext && isString(ext) && b.length > ext.length && b.substring(b.length - ext.length) === ext) {
        b = b.substring(0, b.length - ext.length);
      }
      return b;
    }

    function extname(p) {
      var b = basename(p);
      var idx = b.lastIndexOf('.');
      if (idx <= 0) { return ''; }
      return b.substring(idx);
    }

    return {
      normalize: normalize, join: join, resolve: resolve, relative: relative,
      dirname: dirname, basename: basename, extname: extname, isAbsolute: isAbsolute,
      sep: '/', delimiter: ':',
      parse: function (p) {
        var dir = dirname(p), base = basename(p), ext = extname(p);
        return { root: isAbsolute(p) ? '/' : '', dir: dir, base: base, ext: ext, name: base.substring(0, base.length - ext.length) };
      },
      format: function (o) { return join(o.dir || '', o.base || (o.name || '') + (o.ext || '')); },
    };
  })();

  // ---------------------------------------------------------------- Buffer

  var UTF8 = (function () {
    function encode(s) {
      var out = [];
      for (var i = 0; i < s.length; i++) {
        var c = s.codePointAt(i);
        if (c > 0xFFFF) { i++; }
        if (c < 0x80) { out.push(c); }
        else if (c < 0x800) { out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F)); }
        else { out.push(0xF0 | (c >> 18), 0x80 | ((c >> 12) & 0x3F), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F)); }
      }
      return out;
    }
    function decode(bytes, start, end) {
      var s = '';
      var i = start;
      while (i < end) {
        var b = bytes[i];
        var cp;
        if (b < 0x80) { cp = b; i += 1; }
        else if (b < 0xE0) { cp = ((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F); i += 2; }
        else if (b < 0xF0) { cp = ((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F); i += 3; }
        else { cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F); i += 4; }
        s += String.fromCodePoint(cp);
      }
      return s;
    }
    return { encode: encode, decode: decode };
  })();

  var B64 = (function () {
    var A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    function encode(bytes) {
      var out = '';
      for (var i = 0; i < bytes.length; i += 3) {
        var b1 = bytes[i], b2 = bytes[i + 1], b3 = bytes[i + 2];
        out += A.charAt(b1 >> 2);
        out += A.charAt(((b1 & 3) << 4) | ((b2 === undefined ? 0 : b2) >> 4));
        out += b2 === undefined ? '=' : A.charAt(((b2 & 15) << 2) | ((b3 === undefined ? 0 : b3) >> 6));
        out += b3 === undefined ? '=' : A.charAt(b3 & 63);
      }
      return out;
    }
    function decode(str) {
      str = String(str).replace(/[^A-Za-z0-9+\/]/g, '');
      // charAt past the end yields '' — and ''.indexOf is 0, so map it to -1.
      function idx(ch) { return ch === '' ? -1 : A.indexOf(ch); }
      var out = [];
      for (var i = 0; i < str.length; i += 4) {
        var c0 = idx(str.charAt(i));
        var c1 = idx(str.charAt(i + 1));
        var c2 = idx(str.charAt(i + 2));
        var c3 = idx(str.charAt(i + 3));
        var n = (c0 << 18) | (c1 << 12) | ((c2 < 0 ? 0 : c2) << 6) | (c3 < 0 ? 0 : c3);
        out.push((n >> 16) & 255);
        if (c2 >= 0) { out.push((n >> 8) & 255); }
        if (c3 >= 0) { out.push(n & 255); }
      }
      return out;
    }
    return { encode: encode, decode: decode };
  })();

  var HEX = {
    encode: function (bytes) { var s = ''; for (var i = 0; i < bytes.length; i++) { s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16); } return s; },
    decode: function (str) { var out = []; for (var i = 0; i + 1 < str.length; i += 2) { out.push(parseInt(str.substr(i, 2), 16)); } return out; },
  };

  function Buffer(arg, encoding) {
    var a;
    if (typeof arg === 'number') {
      a = [];
      var n = Math.max(0, Math.floor(arg));
      for (var i = 0; i < n; i++) { a.push(0); }
    } else if (isString(arg)) {
      a = Buffer._encode(arg, encoding || 'utf8');
    } else if (Array.isArray(arg)) {
      a = arg.slice();
    } else if (Buffer.isBuffer(arg)) {
      a = arg.slice();
    } else {
      a = [];
    }
    Object.setPrototypeOf(a, Buffer.prototype);
    return a;
  }
  Buffer.prototype = Object.create(Array.prototype);
  Buffer.prototype.constructor = Buffer;
  Buffer.poolSize = 8192;
  Buffer.constants = { MAX_LENGTH: 0x7fffffff, MAX_SAFE_INTEGER: 0x1fffffffffffff };

  Buffer._encode = function (s, enc) {
    enc = String(enc || 'utf8').toLowerCase();
    if (enc === 'utf8' || enc === 'utf-8') { return UTF8.encode(s); }
    if (enc === 'ascii' || enc === 'latin1' || enc === 'binary') {
      var out = [];
      for (var i = 0; i < s.length; i++) { out.push(s.charCodeAt(i) & 255); }
      return out;
    }
    if (enc === 'base64') { return B64.decode(s); }
    if (enc === 'hex') { return HEX.decode(s); }
    return UTF8.encode(s);
  };
  Buffer._decode = function (bytes, enc, start, end) {
    enc = String(enc || 'utf8').toLowerCase();
    start = start || 0;
    end = end === undefined ? bytes.length : end;
    if (enc === 'utf8' || enc === 'utf-8') { return UTF8.decode(bytes, start, end); }
    if (enc === 'ascii' || enc === 'latin1' || enc === 'binary') {
      var s = '';
      for (var i = start; i < end; i++) { s += String.fromCharCode(bytes[i]); }
      return s;
    }
    if (enc === 'base64') { return B64.encode(bytes.slice(start, end)); }
    if (enc === 'hex') { return HEX.encode(bytes.slice(start, end)); }
    return UTF8.decode(bytes, start, end);
  };

  Buffer.from = function (arg, enc) { return new Buffer(arg, enc); };
  Buffer.alloc = function (n, fill) {
    var b = new Buffer(n);
    if (fill !== undefined && isString(fill) && fill.length) { var fb = Buffer._encode(fill, 'utf8'); for (var i = 0; i < n; i++) { b[i] = fb[i % fb.length]; } }
    else if (typeof fill === 'number') { for (var j = 0; j < n; j++) { b[j] = fill; } }
    return b;
  };
  Buffer.allocUnsafe = function (n) { return new Buffer(n); };
  Buffer.isBuffer = function (x) { return x instanceof Buffer; };
  Buffer.byteLength = function (s, enc) {
    if (Buffer.isBuffer(s)) { return s.length; }
    return Buffer._encode(isString(s) ? s : String(s), enc || 'utf8').length;
  };
  Buffer.compare = function (a, b) { return a.compare(b); };
  Buffer.concat = function (list) {
    var total = 0;
    for (var i = 0; i < list.length; i++) { total += list[i].length; }
    var out = new Buffer(total);
    var off = 0;
    for (var k = 0; k < list.length; k++) {
      for (var j = 0; j < list[k].length; j++) { out[off++] = list[k][j]; }
    }
    return out;
  };
  Buffer.prototype.toString = function (enc, start, end) { return Buffer._decode(this, enc, start, end); };
  Buffer.prototype.slice = Buffer.prototype.subarray = function (start, end) {
    var s = start < 0 ? this.length + start : (start || 0);
    var e = end === undefined ? this.length : (end < 0 ? this.length + end : end);
    var out = new Buffer(Math.max(0, e - s));
    for (var i = 0; i < out.length; i++) { out[i] = this[s + i]; }
    return out;
  };
  Buffer.prototype.indexOf = function (needle, start) {
    start = start || 0;
    var n = Buffer.isBuffer(needle) ? needle : Buffer.from(String(needle));
    outer: for (var i = start; i + n.length <= this.length; i++) {
      for (var j = 0; j < n.length; j++) { if (this[i + j] !== n[j]) { continue outer; } }
      return i;
    }
    return -1;
  };
  Buffer.prototype.fill = function (v) { for (var i = 0; i < this.length; i++) { this[i] = typeof v === 'number' ? v : (isString(v) ? v.charCodeAt(0) : 0); } return this; };
  Buffer.prototype.copy = function (target, tStart, sStart, sEnd) {
    tStart = tStart || 0; sStart = sStart || 0; sEnd = sEnd === undefined ? this.length : sEnd;
    for (var i = 0; i < sEnd - sStart; i++) { target[tStart + i] = this[sStart + i]; }
    return sEnd - sStart;
  };
  Buffer.prototype.equals = function (o) { return this.compare(o) === 0; };
  Buffer.prototype.compare = function (o) {
    var n = Math.min(this.length, o.length);
    for (var i = 0; i < n; i++) { if (this[i] !== o[i]) { return this[i] < o[i] ? -1 : 1; } }
    return this.length === o.length ? 0 : (this.length < o.length ? -1 : 1);
  };
  Buffer.prototype.write = function (s, offset, enc) {
    var b = Buffer._encode(s, enc || 'utf8');
    for (var i = 0; i < b.length; i++) { this[(offset || 0) + i] = b[i]; }
    return b.length;
  };
  Buffer.prototype.readUInt8 = function (o) { return this[o || 0]; };
  Buffer.prototype.writeUInt8 = function (v, o) { this[o || 0] = v & 255; return this; };

  // ---------------------------------------------------------------- events

  function EventEmitter() { this._events = {}; }
  EventEmitter.prototype._events = {};
  EventEmitter.prototype.on = function (ev, fn) {
    (this._events[ev] = this._events[ev] || []).push(fn);
    return this;
  };
  EventEmitter.prototype.addListener = EventEmitter.prototype.on;
  EventEmitter.prototype.once = function (ev, fn) {
    var self = this;
    var wrap = function () { self.removeListener(ev, wrap); fn.apply(self, arguments); };
    return this.on(ev, wrap);
  };
  EventEmitter.prototype.removeListener = function (ev, fn) {
    var list = this._events[ev];
    if (!list) { return this; }
    var idx = list.indexOf(fn);
    if (idx >= 0) { list.splice(idx, 1); }
    return this;
  };
  EventEmitter.prototype.off = EventEmitter.prototype.removeListener;
  EventEmitter.prototype.removeAllListeners = function (ev) { if (ev) { delete this._events[ev]; } else { this._events = {}; } return this; };
  EventEmitter.prototype.emit = function (ev) {
    var list = (this._events[ev] || []).slice();
    var args = Array.prototype.slice.call(arguments, 1);
    for (var i = 0; i < list.length; i++) { list[i].apply(this, args); }
    return list.length > 0;
  };
  EventEmitter.prototype.listeners = function (ev) { return (this._events[ev] || []).slice(); };
  EventEmitter.prototype.listenerCount = function (ev) { return (this._events[ev] || []).length; };
  EventEmitter.prototype.setMaxListeners = function () {};
  EventEmitter.prototype.prependListener = function (ev, fn) { (this._events[ev] = this._events[ev] || []).unshift(fn); return this; };
  EventEmitter.defaultMaxListeners = 10;
  EventEmitter.EventEmitter = EventEmitter;

  // ---------------------------------------------------------------- fs

  var fs = (function () {
    function encodingOf(opts, def) {
      if (isString(opts)) { return opts; }
      return (opts && opts.encoding) || def;
    }
    function asString(data) {
      if (Buffer.isBuffer(data)) { return data.toString('utf8'); }
      if (Array.isArray(data)) { return new Buffer(data).toString('utf8'); }
      return String(data);
    }

    function readFileSync(p, opts) {
      var abs = path.resolve(p);
      var s = bridge.fsRead(abs);
      if (s === null) { throw enoent('open', p); }
      return encodingOf(opts) ? s : Buffer.from(s, 'utf8');
    }
    function writeFileSync(p, data, opts) {
      var abs = path.resolve(p);
      if (!bridge.fsWrite(abs, asString(data))) { throw err("EACCES/EINVAL: cannot write '" + p + "'"); }
      return undefined;
    }
    function appendFileSync(p, data) {
      var abs = path.resolve(p);
      if (!bridge.fsAppend(abs, asString(data))) { throw err("cannot append to '" + p + "'"); }
    }
    function existsSync(p) { return bridge.fsExists(path.resolve(p)); }
    function statSync(p) {
      var abs = path.resolve(p);
      var isDir = bridge.fsIsDir(abs);
      var isFile = bridge.fsExists(abs) && !isDir;
      if (!isDir && !isFile) { throw enoent('stat', p); }
      var size = isFile ? bridge.fsSize(abs) : 0;
      var mtime = bridge.fsMtime(abs);
      return {
        isFile: function () { return isFile; },
        isDirectory: function () { return isDir; },
        isSymbolicLink: function () { return false; },
        size: size, mtimeMs: mtime, mtime: new Date(mtime),
        atimeMs: mtime, ctimeMs: mtime, birthtimeMs: mtime,
        name: path.basename(p),
      };
    }
    function readdirSync(p) {
      var list = bridge.fsList(path.resolve(p));
      if (list === null) { throw enoent('scandir', p); }
      return list;
    }
    function mkdirSync(p, opts) {
      if (!bridge.fsMkdir(path.resolve(p))) { throw err("cannot mkdir '" + p + "'"); }
      return undefined;
    }
    function rmSync(p, opts) {
      if (!bridge.fsDelete(path.resolve(p))) { throw enoent('rm', p); }
      return undefined;
    }
    var unlinkSync = rmSync;
    var rmdirSync = rmSync;
    function renameSync(a, b) {
      if (!bridge.fsRename(path.resolve(a), path.resolve(b))) { throw err("cannot rename '" + a + "' -> '" + b + "'"); }
    }
    function accessSync(p) { if (!existsSync(p)) { throw enoent('access', p); } }
    function fakeAsync(syncFn) {
      return function (p, a, b) {
        var cb = typeof a === 'function' ? a : b;
        setImmediate(function () {
          try { var r = syncFn(p, typeof a === 'function' ? undefined : a); cb(null, r); }
          catch (e) { cb(e); }
        });
      };
    }
    return {
      readFileSync: readFileSync, writeFileSync: writeFileSync, appendFileSync: appendFileSync,
      existsSync: existsSync, statSync: statSync, readdirSync: readdirSync, mkdirSync: mkdirSync,
      unlinkSync: unlinkSync, rmSync: rmSync, rmdirSync: rmdirSync, renameSync: renameSync,
      accessSync: accessSync,
      readFile: fakeAsync(readFileSync), writeFile: fakeAsync(writeFileSync),
      appendFile: fakeAsync(appendFileSync), stat: fakeAsync(statSync),
      readdir: fakeAsync(readdirSync), mkdir: fakeAsync(mkdirSync),
      unlink: fakeAsync(unlinkSync), rename: fakeAsync(renameSync),
      access: fakeAsync(accessSync), exists: function (p, cb) { setImmediate(function () { cb(existsSync(p)); }); },
      constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 },
      createReadStream: function () { throw err('fs.createReadStream is not supported in PocketRun'); },
      createWriteStream: function () { throw err('fs.createWriteStream is not supported in PocketRun'); },
    };
  })();

  // ---------------------------------------------------------------- url / querystring

  var url = (function () {
    var RE = /^(?:([a-zA-Z][a-zA-Z0-9+.-]*):)?(?:\/\/([^\/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/;
    function parseParts(u) {
      var m = RE.exec(u) || [];
      var auth = m[2] || '';
      var at = auth.lastIndexOf('@');
      var userinfo = at >= 0 ? auth.substring(0, at) : '';
      var host = at >= 0 ? auth.substring(at + 1) : auth;
      var col = host.lastIndexOf(':');
      var hostname = col >= 0 ? host.substring(0, col) : host;
      var port = col >= 0 ? host.substring(col + 1) : '';
      return {
        protocol: m[1] ? m[1] + ':' : null,
        slashes: u.indexOf('//') >= 0,
        auth: userinfo || null,
        host: host || null,
        hostname: hostname || null,
        port: port || null,
        pathname: m[3] || null,
        search: m[4] !== undefined && m[4] !== null ? '?' + m[4] : null,
        query: m[4] !== undefined && m[4] !== null ? m[4] : null,
        hash: m[5] !== undefined && m[5] !== null ? '#' + m[5] : null,
        href: u,
      };
    }
    function URLSearchParamsInit(qs) {
      this._pairs = [];
      if (qs) {
        qs = String(qs).replace(/^\?/, '');
        var parts = qs.length ? qs.split('&') : [];
        for (var i = 0; i < parts.length; i++) {
          var eq = parts[i].indexOf('=');
          var k = eq < 0 ? parts[i] : parts[i].substring(0, eq);
          var v = eq < 0 ? '' : parts[i].substring(eq + 1);
          this._pairs.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]);
        }
      }
    }
    URLSearchParamsInit.prototype.get = function (k) { for (var i = 0; i < this._pairs.length; i++) { if (this._pairs[i][0] === k) { return this._pairs[i][1]; } } return null; };
    URLSearchParamsInit.prototype.getAll = function (k) { var r = []; for (var i = 0; i < this._pairs.length; i++) { if (this._pairs[i][0] === k) { r.push(this._pairs[i][1]); } } return r; };
    URLSearchParamsInit.prototype.has = function (k) { return this.get(k) !== null; };
    URLSearchParamsInit.prototype.set = function (k, v) { var done = false; var out = []; for (var i = 0; i < this._pairs.length; i++) { if (this._pairs[i][0] === k) { if (!done) { out.push([k, v]); done = true; } } else { out.push(this._pairs[i]); } } if (!done) { out.push([k, v]); } this._pairs = out; };
    URLSearchParamsInit.prototype.append = function (k, v) { this._pairs.push([k, v]); };
    URLSearchParamsInit.prototype.delete = function (k) { this._pairs = this._pairs.filter(function (p) { return p[0] !== k; }); };
    URLSearchParamsInit.prototype.forEach = function (fn) { for (var i = 0; i < this._pairs.length; i++) { fn(this._pairs[i][1], this._pairs[i][0]); } };
    URLSearchParamsInit.prototype.toString = function () { return this._pairs.map(function (p) { return encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1]); }).join('&'); };

    function URL(u) {
      var p = parseParts(u);
      this.href = u;
      this.protocol = p.protocol || 'http:';
      this.host = p.host || '';
      this.hostname = p.hostname || '';
      this.port = p.port || '';
      this.pathname = p.pathname || '/';
      this.search = p.search || '';
      this.hash = p.hash || '';
      this.query = p.query;
      this.searchParams = new URLSearchParamsInit(this.search);
      var self = this;
      this.toString = function () { return self.href; };
      this.toJSON = function () { return self.href; };
    }

    function parse(u) {
      var p = parseParts(u);
      p.path = (p.pathname || '') + (p.search || '');
      p.query = p.query || null;
      return p;
    }
    function format(o) {
      if (isString(o)) { return o; }
      var out = (o.protocol || 'http:') + '//';
      if (o.auth) { out += o.auth + '@'; }
      out += o.host || ((o.hostname || '') + (o.port ? ':' + o.port : ''));
      out += o.path || (o.pathname || '') + (o.search || '');
      if (o.hash) { out += o.hash; }
      return out;
    }
    return { parse: parse, format: format, URL: URL, URLSearchParams: URLSearchParamsInit, resolve: function (a, b) { return format(parse(b)); } };
  })();

  var querystring = {
    parse: function (qs) { var sp = new url.URLSearchParams(qs); var o = {}; sp.forEach(function (v, k) { o[k] = v; }); return o; },
    stringify: function (o) { var sp = new url.URLSearchParams(''); Object.keys(o).forEach(function (k) { if (o[k] !== undefined && o[k] !== null) { sp.append(k, o[k]); } }); return sp.toString(); },
    escape: encodeURIComponent, unescape: decodeURIComponent,
  };

  // ---------------------------------------------------------------- os

  var os = {
    platform: function () { return 'android'; },
    arch: function () { return 'arm64'; },
    type: function () { return 'Linux'; },
    release: function () { return ''; },
    hostname: function () { return 'localhost'; },
    homedir: function () { return ROOT; },
    tmpdir: function () { return path.join(ROOT, 'cache', 'tmp'); },
    EOL: '\n',
    cpus: function () { return []; },
    totalmem: function () { return 0; },
    freemem: function () { return 0; },
    uptime: function () { return 0; },
    loadavg: function () { return [0, 0, 0]; },
    networkInterfaces: function () { return {}; },
  };

  // ---------------------------------------------------------------- http / https

  function httpPerform(method, target, headers, body) {
    var respJson = bridge.http(method, target, JSON.stringify(headers || {}), body === undefined || body === null ? null : String(body));
    if (respJson === null) { return null; }
    try { return JSON.parse(respJson); } catch (e) { return null; }
  }

  function makeRequest(isHttps) {
    return function (options, cb) {
      var method = 'GET', target = '', headers = {};
      if (isString(options)) {
        target = options;
      } else if (options && options.href) {
        target = options.href;
      } else if (options) {
        method = (options.method || 'GET').toUpperCase();
        var proto = options.protocol || (isHttps ? 'https:' : 'http:');
        var host = options.hostname || options.host || 'localhost';
        var port = options.port !== undefined && options.port !== null && String(options.port) !== '' ? ':' + options.port : '';
        var p = options.path || '/';
        target = proto + '//' + host + port + p;
      } else { throw err('http.request: options required'); }
      if (options && !isString(options) && options.method) { method = String(options.method).toUpperCase(); }
      if (options && !isString(options) && options.headers) { Object.keys(options.headers).forEach(function (k) { headers[k] = String(options.headers[k]); }); }

      var req = new EventEmitter();
      var chunks = [];
      var ended = false;
      req.write = function (d) { chunks.push(Buffer.isBuffer(d) ? d.toString('utf8') : String(d)); return true; };
      req.end = function (d) {
        if (ended) { return req; }
        ended = true;
        if (d !== undefined && d !== null) { req.write(d); }
        var body = chunks.join('');
        var resp = httpPerform(method, target, headers, body);
        if (resp === null) {
          req.emit('error', err('network request failed: ' + method + ' ' + target));
          return req;
        }
        var res = new EventEmitter();
        res.statusCode = resp.status;
        res.statusMessage = '';
        res.headers = resp.headers || {};
        res.url = target;
        if (cb) { cb(res); }
        var data = Buffer.from(resp.body || '', 'utf8');
        if (data.length) { res.emit('data', data); }
        res.emit('end');
        return req;
      };
      req.abort = function () {};
      req.setHeader = function (k, v) { headers[k] = String(v); };
      req.getHeader = function (k) { return headers[k]; };
      req.setTimeout = function (ms, fn) { if (fn) { fn(); } return req; };
      req.setNoDelay = function () { return req; };
      return req;
    };
  }

  function makeHttp(isHttps) {
    var request = makeRequest(isHttps);
    return {
      request: request,
      get: function (options, cb) { var r = request(options, cb); r.end(); return r; },
      STATUS_CODES: {},
      Agent: function () { return { destroy: function () {} }; },
    };
  }
  var http = makeHttp(false);
  var https = makeHttp(true);

  // ---------------------------------------------------------------- assert

  var assert = (function () {
    function fail(msg) { throw err('AssertionError: ' + (msg || 'failed')); }
    function ok(v, msg) { if (!v) { fail(msg || 'value is not truthy'); } }
    function equal(a, b, msg) { ok(a == b, msg || (inspectValue(a, 2, []) + ' == ' + inspectValue(b, 2, []))); }
    function strictEqual(a, b, msg) { ok(a === b, msg || (inspectValue(a, 2, []) + ' === ' + inspectValue(b, 2, []))); }
    function deepEqual(a, b, msg) { ok(JSON.stringify(a) === JSON.stringify(b), msg || 'deep values differ'); }
    function notEqual(a, b, msg) { ok(a != b, msg); }
    function notStrictEqual(a, b, msg) { ok(a !== b, msg); }
    function throws(fn, msg) {
      try { fn(); } catch (e) { return; }
      fail(msg || 'missing expected exception');
    }
    return {
      fail: fail, ok: ok, equal: equal, notEqual: notEqual, strictEqual: strictEqual,
      notStrictEqual: notStrictEqual, deepEqual: deepEqual, notDeepEqual: function (a, b, m) { ok(JSON.stringify(a) !== JSON.stringify(b), m); },
      throws: throws, doesNotThrow: function (fn) { fn(); },
      ifError: function (v) { if (v) { fail(v); } },
    };
  })();
  assert.strict = assert;

  // ---------------------------------------------------------------- Promise (if native is missing)

  if (typeof global.Promise === 'undefined' || !global.Promise) {
    (function () {
      function P(fn) {
        if (!(this instanceof P)) { throw err('Promise must be called with new'); }
        this._state = 'pending';
        this._value = undefined;
        this._cbs = [];
        var self = this;
        try {
          fn(function (v) { settle(self, 'fulfilled', v); }, function (v) { settle(self, 'rejected', v); });
        } catch (e) { settle(self, 'rejected', e); }
      }
      function settle(p, state, value) {
        if (p._state !== 'pending') { return; }
        if (value === p) { p._state = 'rejected'; p._value = err('Chaining cycle detected'); schedule(p); return; }
        var then;
        try { then = value && (typeof value === 'object' || typeof value === 'function') && value.then; } catch (e) { settle(p, 'rejected', e); return; }
        if (typeof then === 'function') {
          var done = false;
          try {
            then.call(value, function (v) { if (!done) { done = true; settle(p, 'fulfilled', v); } }, function (v) { if (!done) { done = true; settle(p, 'rejected', v); } });
          } catch (e) { if (!done) { done = true; settle(p, 'rejected', e); } }
          return;
        }
        p._state = state;
        p._value = value;
        schedule(p);
      }
      function schedule(p) {
        for (var i = 0; i < p._cbs.length; i++) { run(p, p._cbs[i]); }
        p._cbs = null;
      }
      function run(p, cb) {
        setImmediate(function () {
          var next = cb.p;
          var x;
          try {
            var fn = p._state === 'fulfilled' ? cb.onOk : cb.onErr;
            if (typeof fn !== 'function') {
              settleNext(next, p._state, p._value);
              return;
            }
            x = fn(p._value);
            settleNext(next, 'fulfilled', x);
          } catch (e) {
            settleNext(next, 'rejected', e);
          }
        });
      }
      function settleNext(p, state, v) { if (p) { settle(p, state, v); } }
      P.prototype.then = function (onOk, onErr) {
        var self = this;
        var next = new P(function () {});
        var cb = { onOk: onOk, onErr: onErr, p: next };
        if (self._state === 'pending') { self._cbs.push(cb); } else { run(self, cb); }
        return next;
      };
      P.prototype.catch = function (onErr) { return this.then(undefined, onErr); };
      P.prototype.finally = function (fn) { return this.then(fn, fn); };
      P.resolve = function (v) { return new P(function (res) { res(v); }); };
      P.reject = function (v) { return new P(function (_, rej) { rej(v); }); };
      P.all = function (list) {
        return new P(function (resolve, reject) {
          var out = new Array(list.length);
          var left = list.length;
          if (left === 0) { return resolve(out); }
          list.forEach(function (item, i) {
            P.resolve(item).then(function (v) {
              out[i] = v;
              if (--left === 0) { resolve(out); }
            }, reject);
          });
        });
      };
      P.race = function (list) {
        return new P(function (resolve, reject) {
          list.forEach(function (item) { P.resolve(item).then(resolve, reject); });
        });
      };
      global.Promise = P;
    })();
  }

  // ---------------------------------------------------------------- modules

  var builtins = {
    path: path, fs: fs, os: os, util: util, url: url, querystring: querystring,
    events: { EventEmitter: EventEmitter },
    assert: assert, buffer: { Buffer: Buffer, SlowBuffer: Buffer },
    timers: { setTimeout: setTimeout, clearTimeout: clearTimeout, setInterval: setInterval, clearInterval: clearInterval, setImmediate: setImmediate, clearImmediate: clearImmediate },
    http: http, https: https,
    string_decoder: { StringDecoder: function (enc) { this.encoding = enc || 'utf8'; this.write = function (b) { return Buffer.isBuffer(b) ? b.toString(this.encoding) : String(b); }; this.end = function (b) { return b === undefined ? '' : this.write(b); }; } },
    stream: (function () {
      function Stream() { EventEmitter.call(this); }
      util.inherits(Stream, EventEmitter);
      Stream.prototype.pipe = function () { return this; };
      function Writable() { Stream.call(this); }
      util.inherits(Writable, Stream);
      Writable.prototype.write = function (c, e, cb) { var fn = typeof e === 'function' ? e : cb; if (fn) { fn(); } this.emit('drain'); return true; };
      Writable.prototype.end = function (c, e, cb) { var fn = typeof e === 'function' ? e : cb; this.emit('finish'); if (fn) { fn(); } return this; };
      function Readable() { Stream.call(this); }
      util.inherits(Readable, Stream);
      Readable.prototype.push = function (d) { if (d !== null) { this.emit('data', Buffer.isBuffer(d) ? d : Buffer.from(String(d))); } return true; };
      function Duplex() { Writable.call(this); Readable.call(this); }
      util.inherits(Duplex, Writable);
      function Transform() { Duplex.call(this); }
      util.inherits(Transform, Duplex);
      Transform.prototype._transform = function () {};
      return { Stream: Stream, Writable: Writable, Readable: Readable, Duplex: Duplex, Transform: Transform, PassThrough: Transform };
    })(),
  };

  var moduleCache = new Map();

  function loadAsFile(p) {
    if (bridge.fsExists(p) && !bridge.fsIsDir(p)) { return p; }
    if (bridge.fsExists(p + '.js') && !bridge.fsIsDir(p + '.js')) { return p + '.js'; }
    if (bridge.fsExists(p + '.json') && !bridge.fsIsDir(p + '.json')) { return p + '.json'; }
    return null;
  }
  function loadAsDir(p) {
    if (!bridge.fsIsDir(p)) { return null; }
    var pj = path.join(p, 'package.json');
    if (bridge.fsExists(pj)) {
      try {
        var meta = JSON.parse(bridge.fsRead(pj));
        if (meta.main) {
          var main = path.join(p, String(meta.main));
          var f = loadAsFile(main) || loadAsFile(path.join(main, 'index.js')) || loadAsFile(path.join(main, 'index.json'));
          if (f) { return f; }
        }
      } catch (e) { /* fall through to index */ }
    }
    return loadAsFile(path.join(p, 'index.js')) || loadAsFile(path.join(p, 'index.json'));
  }

  function loadModule(file) {
    if (moduleCache.has(file)) { return moduleCache.get(file).exports; }
    var mod = { id: file, filename: file, exports: {}, loaded: false };
    moduleCache.set(file, mod);
    var src = bridge.fsRead(file);
    if (src === null) { throw err("Cannot read module '" + file + "'"); }
    // Bin scripts may be required as libraries; strip their shebang too.
    if (src.charCodeAt(0) === 35) { src = src.replace(/^#![^\n]*\n?/, ''); }
    var dir = path.dirname(file);
    if (file.endsWith('.json')) {
      mod.exports = JSON.parse(src);
    } else {
      var wrapper;
      try {
        wrapper = new Function('exports', 'require', 'module', '__filename', '__dirname',
          src + '\n//# sourceURL=' + file);
      } catch (e) {
        throw err("Cannot parse module '" + file + "': " + (e && e.message ? e.message : String(e)));
      }
      wrapper.call(mod.exports, mod.exports, function (id) { return requireFrom(id, dir); }, mod, file, dir);
    }
    mod.loaded = true;
    return mod.exports;
  }

  function resolveInNodeModules(id, fromDir) {
    var dir = fromDir;
    for (;;) {
      var candidate = path.join(dir, 'node_modules', id);
      var f = loadAsFile(candidate) || loadAsDir(candidate);
      if (f) { return f; }
      // PocketRun installs top-level packages into packages/, not node_modules/.
      candidate = path.join(dir, 'packages', id);
      f = loadAsFile(candidate) || loadAsDir(candidate);
      if (f) { return f; }
      var parent = path.dirname(dir);
      if (parent === dir || dir === ROOT) { return null; }
      dir = parent;
    }
  }

  function requireFrom(id, fromDir) {
    if (!isString(id)) { throw err('require() argument must be a string'); }
    if (id.charAt(0) === '.' || id.charAt(0) === '/') {
      var base = id.charAt(0) === '/' ? id : path.join(fromDir, id);
      var f = loadAsFile(path.normalize(base)) || loadAsDir(path.normalize(base));
      if (!f) { throw err("Cannot find module '" + id + "' from '" + fromDir + "'"); }
      return loadModule(f);
    }
    if (builtins[id]) { return builtins[id]; }
    var resolved = resolveInNodeModules(id, fromDir);
    if (!resolved) { throw err("Cannot find module '" + id + "' (npm package not installed? try run_npx)"); }
    return loadModule(resolved);
  }

  // ---------------------------------------------------------------- install globals

  global.setTimeout = setTimeout;
  global.clearTimeout = clearTimeout;
  global.setInterval = setInterval;
  global.clearInterval = clearInterval;
  global.setImmediate = setImmediate;
  global.clearImmediate = clearImmediate;
  global.process = process;
  global.console = console;
  global.Buffer = Buffer;
  // The main script resolves relative requires against its own directory, like
  // Node does for the entry module; the Kotlin side sets it after boot.
  var mainDir = null;
  global.__setMainDir = function (d) { mainDir = d; };
  global.require = function (id) { return requireFrom(id, mainDir || process.cwd()); };
  global.global = global;
  if (typeof global.globalThis === 'undefined') { global.globalThis = global; }
  global.__pocketrun = {
    builtins: builtins,
    EventEmitter: EventEmitter,
  };
  delete global.__prRunDueHidden;
})(this);
