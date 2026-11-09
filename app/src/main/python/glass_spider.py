# -*- coding: utf-8 -*-
"""柔光影视 · Python 源运行时（Chaquopy）。

支持两种第三方 .py 形态（02 §3）：
A. 模块级函数 home/homeVod/category/detail/search；
B. TVBox class Spider 形态：init(ext) + homeContent/categoryContent/detailContent/searchContent。

同时提供 requests / base.spider 兼容垫片，网络统一走 urllib 并做编码兜底，
异常全部转成 JSON，不允许击穿到 Kotlin 层。
"""

import base64
import hashlib
import inspect
import json
import re
import sys
import time
import traceback
import types
import urllib.parse
import urllib.request

_CACHE = {}
_CACHE_ORDER = []
_MAX_OUTPUT = 4 * 1024 * 1024
UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) GlassBox/1.0"


# ---------------- HTTP ----------------

def _decode(raw, charset=None):
    candidates = []
    if charset:
        candidates.append(charset)
    head = raw[:512].decode("iso-8859-1", errors="ignore")
    match = re.search(r'encoding\s*=\s*["\']([A-Za-z0-9_\-]+)', head, re.I)
    if match:
        candidates.append(match.group(1))
    candidates += ["utf-8", "gb18030"]
    for name in candidates:
        try:
            if name.lower().replace("_", "-") in ("gb2312", "gbk", "gb18030"):
                return raw.decode("gb18030", errors="strict")
            return raw.decode(name, errors="strict")
        except (LookupError, UnicodeDecodeError):
            continue
    return raw.decode("utf-8", errors="replace")


def _http(url, headers=None, data=None, timeout=15, method=None):
    url = str(url or "").strip()
    if not url.startswith("http://") and not url.startswith("https://"):
        url = "https://" + url
    hdrs = {"User-Agent": UA, "Accept": "application/json, text/plain, */*"}
    if headers:
        if isinstance(headers, str):
            try:
                headers = json.loads(headers)
            except Exception:
                headers = {}
        if isinstance(headers, dict):
            hdrs.update({str(k): str(v) for k, v in headers.items()})
    body = None if data is None else (data.encode("utf-8") if isinstance(data, str) else data)
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        return _decode(raw, resp.headers.get_content_charset())


class _Response(object):
    def __init__(self, text, url, status=200):
        self.text = text
        self.url = url
        self.status_code = status
        self.encoding = "utf-8"
        self.headers = {}
        self.cookies = {}
        self.reason = "OK" if status < 400 else "HTTP %d" % status

    @property
    def content(self):
        return self.text.encode("utf-8")

    @property
    def ok(self):
        return self.status_code < 400

    def json(self, **kwargs):
        return json.loads(self.text)

    def iter_content(self, chunk_size=1024, **kwargs):
        data = self.text.encode("utf-8")
        for i in range(0, len(data), chunk_size):
            yield data[i:i + chunk_size]

    def close(self):
        pass

    def raise_for_status(self):
        if self.status_code >= 400:
            raise ValueError("HTTP %d" % self.status_code)


def _requests_get(url, params=None, headers=None, timeout=15, **kwargs):
    if params:
        sep = "&" if "?" in str(url) else "?"
        url = str(url) + sep + urllib.parse.urlencode(params)
    return _Response(_http(url, headers, None, timeout), url)


def _requests_post(url, data=None, json=None, headers=None, timeout=15, **kwargs):
    payload = None
    hdrs = dict(headers or {})
    if json is not None:
        payload = __import__("json").dumps(json, ensure_ascii=False)
        hdrs.setdefault("Content-Type", "application/json")
    elif data is not None:
        payload = data if isinstance(data, str) else urllib.parse.urlencode(data)
        hdrs.setdefault("Content-Type", "application/x-www-form-urlencoded")
    return _Response(_http(url, hdrs, payload, timeout, method="POST"), str(url))


def _requests_request(method, url, **kwargs):
    method = str(method or "GET").upper()
    if method == "POST":
        return _requests_post(url, **kwargs)
    return _requests_get(url, **kwargs)


def _install_shims():
    # urllib3：很多源用 `import requests, urllib3; urllib3.disable_warnings()` 来
    # 关闭 SSL 警告，缺这个模块会导致它们把 requests 置为 None（实测 2048ai.py）。
    if "urllib3" not in sys.modules:
        u3 = types.ModuleType("urllib3")
        u3.disable_warnings = lambda *a, **k: None
        u3.exceptions = types.SimpleNamespace(
            HTTPError=Exception, SSLError=Exception, MaxRetryError=Exception
        )
        sys.modules["urllib3"] = u3

    if "requests" not in sys.modules:
        requests_mod = types.ModuleType("requests")
        exceptions_mod = types.ModuleType("requests.exceptions")

        class _RequestException(Exception):
            pass

        class _Timeout(_RequestException):
            pass

        exceptions_mod.RequestException = _RequestException
        exceptions_mod.Timeout = _Timeout
        exceptions_mod.ConnectTimeout = _Timeout
        exceptions_mod.ReadTimeout = _Timeout
        exceptions_mod.ConnectionError = _RequestException
        exceptions_mod.HTTPError = _RequestException
        requests_mod.exceptions = exceptions_mod

        class _Session(object):
            def __init__(self):
                self.headers = {}
                self.verify = True
                self.trust_env = True
                self.proxies = {}

            def get(self, url, **kw):
                kw.setdefault("headers", self.headers)
                return _requests_get(url, **kw)

            def post(self, url, **kw):
                kw.setdefault("headers", self.headers)
                return _requests_post(url, **kw)

            def request(self, method, url, **kw):
                kw.setdefault("headers", self.headers)
                return _requests_request(method, url, **kw)

            def close(self):
                pass

        requests_mod.get = _requests_get
        requests_mod.post = _requests_post
        requests_mod.request = _requests_request
        requests_mod.RequestException = _RequestException
        requests_mod.Session = _Session
        requests_mod.session = lambda: _Session()
        sys.modules["requests"] = requests_mod
        sys.modules["requests.exceptions"] = exceptions_mod

    if "base.spider" not in sys.modules:
        base_mod = types.ModuleType("base")
        spider_mod = types.ModuleType("base.spider")

        class Spider(object):
            def init(self, extend=""):
                pass

            def getName(self):
                return ""

            def getProxyUrl(self, local=True):
                # 本版没有本地代理服务；返回空串让脚本走直链。
                return ""

            def isVideoFormat(self, url):
                return bool(re.search(r"\.(m3u8|mp4)(\?|$)", str(url or ""), re.I))

            def manualVideoCheck(self):
                return False

            def localProxy(self, param):
                return None

            def destroy(self):
                pass

        spider_mod.Spider = Spider
        base_mod.spider = spider_mod
        sys.modules["base"] = base_mod
        sys.modules["base.spider"] = spider_mod
        sys.modules["base.base_spider"] = spider_mod


_install_shims()


# ---------------- 加载与调用 ----------------

def _join_url(*parts):
    value = ""
    for part in parts:
        if not part:
            continue
        if not value:
            value = str(part)
        else:
            value = value.rstrip("/") + "/" + str(part).lstrip("/")
    return value


def _reg_str(pattern, text, group=1):
    try:
        match = re.search(pattern, str(text))
        return match.group(group) if match else ""
    except Exception:
        return ""


def _helpers():
    return {
        "http_get": lambda url, headers=None: _http(url, headers),
        "http_post": lambda url, body="", headers=None: _http(url, headers, body),
        "fetch": lambda url, headers=None, **kw: _http(url, headers),
        "post": lambda url, data=None, headers=None, **kw: _http(url, headers, data, method="POST"),
        "getProxyUrl": lambda local=True: "",
        "joinUrl": _join_url,
        "urljoin": _join_url,
        "regStr": _reg_str,
        "base64_encode": lambda s: base64.b64encode(str(s).encode()).decode(),
        "base64_decode": lambda s: base64.b64decode(str(s)).decode("utf-8", "replace"),
        "md5": lambda s: hashlib.md5(str(s).encode()).hexdigest(),
        "log": lambda *args: print("[GlassPy]", *args),
        "time": time,
        "json": json,
        "requests": sys.modules.get("requests"),
    }


def _diag(code, ns):
    """把脚本状态压缩成一行，进错误消息，避免再出现「没有日志就靠猜」。"""
    first = ""
    for line in str(code).splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            first = stripped[:36]
            break
    has_cls = isinstance(ns.get("Spider"), type)
    funcs = [
        name for name in (
            "home", "homeContent", "homeVod", "category", "categoryContent",
            "detail", "detailContent", "search", "searchContent", "player", "playerContent",
        ) if callable(ns.get(name))
    ]
    return "code=%d字节 首行=%r classSpider=%s 模块函数=%s" % (len(str(code)), first, has_cls, funcs)


def reset(code=None):
    """超时后重建：清掉已编译模块与 Spider 实例，后续请求不会打在死连接上。"""
    if code is None:
        _CACHE.clear()
        del _CACHE_ORDER[:]
    else:
        key = str(hash(code))
        _CACHE.pop(key, None)
        try:
            _CACHE_ORDER.remove(key)
        except ValueError:
            pass
    return "ok"


def _load(code, path=""):
    key = str(hash(code)) + "|" + str(path)
    if key in _CACHE:
        return _CACHE[key]
    code = code.lstrip("\ufeff")
    # 原版 Chaquopy 用 exec 加载真实文件，脚本里常直接使用 __file__ / __name__；
    # 缺 __file__ 会抛 `NameError: name '__file__' is not defined`（实测「自动加载.py」）。
    filename = path or "<glass-source>"
    ns = {
        "__name__": "glass_source",
        "__file__": filename,
        "__package__": None,
        "__cached__": None,
        "__spec__": None,
        "__loader__": None,
        "__builtins__": __builtins__,
    }
    ns.update(_helpers())
    exec(compile(code, filename, "exec"), ns)
    _CACHE[key] = ns
    _CACHE_ORDER.append(key)
    while len(_CACHE_ORDER) > 4:
        _CACHE.pop(_CACHE_ORDER.pop(0), None)
    return ns


def _parse_ext(ext):
    if isinstance(ext, (dict, list)):
        return ext
    if isinstance(ext, str) and ext.strip():
        try:
            return json.loads(ext)
        except Exception:
            return ext.strip()
    return ""


def _instance(ns, ext):
    cls = ns.get("Spider")
    if not isinstance(cls, type) or not _looks_like_spider(cls):
        cls = None
        for value in list(ns.values()):
            if isinstance(value, type) and _looks_like_spider(value):
                cls = value
                break
    if not isinstance(cls, type):
        return None
    cache = ns.setdefault("__instances__", {})
    key = ext if isinstance(ext, str) else json.dumps(ext, ensure_ascii=False, default=str)
    if key in cache:
        return cache[key]
    obj = cls()
    init = getattr(obj, "init", None)
    if callable(init):
        parsed = _parse_ext(ext)
        try:
            init(parsed)
        except TypeError:
            try:
                init()
            except Exception:
                pass
        except Exception:
            pass
    cache[key] = obj
    return obj


def _looks_like_spider(value):
    if not isinstance(value, type) or value.__name__ == "Spider" and value.__module__ == "base.spider":
        return False
    return any(
        callable(getattr(value, name, None))
        for name in ("homeContent", "home", "homeVod", "categoryContent", "detailContent", "searchContent")
    )


def _meta(ns, ext):
    for name in ("site", "rule"):
        value = ns.get(name)
        if isinstance(value, dict):
            return value
    cls = ns.get("Spider")
    if isinstance(cls, type):
        for name in ("site", "rule"):
            value = getattr(cls, name, None)
            if isinstance(value, dict):
                return value
    parsed = _parse_ext(ext)
    if isinstance(parsed, dict):
        return parsed
    return {}


def _first_line(code):
    for line in str(code).splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            return stripped[:36]
    return ""


def _fit_args(fn, args):
    """按签名截断参数（照搬原版 runner.py 的做法），避免用 TypeError 反复重试
    导致同一个网络请求被执行多次。"""
    try:
        sig = inspect.signature(fn)
    except (TypeError, ValueError):
        return fn(*args)
    params = list(sig.parameters.values())
    if any(p.kind == p.VAR_POSITIONAL for p in params):
        return fn(*args)
    positional = [p for p in params if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
    required = [p for p in positional if p.default is p.empty]
    if len(args) < len(required):
        raise TypeError("%s 需要 %d 个参数，实际 %d 个" % (
            getattr(fn, "__name__", "fn"), len(required), len(args)
        ))
    return fn(*args[:len(positional)])


def _call_first(obj_or_ns, names, arg_variants):
    """按候选函数名与候选参数依次尝试，返回 (函数名, 结果)。"""
    last_error = None
    for name in names:
        fn = obj_or_ns.get(name) if isinstance(obj_or_ns, dict) else getattr(obj_or_ns, name, None)
        if not callable(fn):
            continue
        for args in arg_variants:
            try:
                return name, _fit_args(fn, args)
            except TypeError as e:
                last_error = e
                continue
        if last_error:
            raise last_error
    return None, None


def _count(data):
    if isinstance(data, dict):
        lst = data.get("list") or data.get("videos") or data.get("data")
        if isinstance(lst, list):
            return len(lst)
        classes = data.get("class")
        if isinstance(classes, list):
            return len(classes)
        return 0
    if isinstance(data, list):
        total = 0
        for item in data:
            if isinstance(item, dict) and isinstance(item.get("items") or item.get("list"), list):
                total += len(item.get("items") or item.get("list") or [])
            else:
                total += 1
        return total
    return 0


def _sample(data):
    def first_of(items):
        if isinstance(items, list) and items and isinstance(items[0], dict):
            row = items[0]
            return row.get("vod_name") or row.get("title") or row.get("name") or ""
        return ""

    if isinstance(data, dict):
        return first_of(data.get("list") or data.get("videos") or [])
    if isinstance(data, list):
        for item in data:
            if isinstance(item, dict):
                got = first_of(item.get("items") or item.get("list") or []) or item.get("title") or ""
                if got:
                    return got
    return ""


def _safe(fn):
    def wrapper(*args):
        try:
            return fn(*args)
        except Exception:
            return None
    return wrapper


# ---------------- 对外接口 ----------------

def probe(code, ext="{}", path=""):
    try:
        ns = _load(code, path)
        obj = _instance(ns, ext)
        meta = _meta(ns, ext)
        target = obj if obj is not None else ns
        used, data = _call_first(
            target,
            ["home", "homeContent", "homeVod", "homeVideoContent"],
            [(), (False,), ({},)],
        )
        count = _count(data)
        sample = _sample(data)
        ok = used is not None and count > 0
        message = (
            "CPython 已执行 %s()，返回 %d 项" % (used, count)
            if used else "脚本未导出 home/homeContent（也不是 class Spider 形态）：" + _diag(code, ns)
        )
        return _dump({
            "ok": ok,
            "meta": meta,
            "homeCount": count,
            "sample": sample,
            "message": message,
        })
    except Exception:
        # 完整栈打到 logcat（python.stdout），UI 只展示最后一行
        traceback.print_exc()
        return _dump({
            "ok": False,
            "message": "PY 执行失败（%d 字节，首行=%r）：%s" % (
                len(str(code)), _first_line(code), _last_line()
            ),
        })


def invoke(code, fn, args_json="[]", ext="{}", path=""):
    try:
        ns = _load(code, path)
        obj = _instance(ns, ext)
        target = obj if obj is not None else ns
        args = json.loads(args_json or "[]")
        names = {
            "home": ["home", "homeContent", "homeVod"],
            "category": ["category", "categoryContent"],
            "detail": ["detail", "detailContent"],
            "search": ["search", "searchContent"],
            "player": ["playerContent", "player"],
        }.get(fn, [fn])
        if fn == "category":
            page = int(args[1]) if len(args) > 1 else 1
            variants = [args, [args[0], page, False, {}], [args[0], page]]
        elif fn == "detail":
            # class Spider.detailContent(List<String> ids) 需要「列表」作为第一个参数；
            # 模块式 detail(id) 需要裸字符串。按目标形态决定优先级。
            if not args:
                variants = [[]]
            elif isinstance(target, dict):
                variants = [(args[0],), ([args[0]],), (args,)]
            else:
                variants = [([args[0]],), (args[0],), (args,)]
        elif fn == "player":
            flag = str(args[0]) if len(args) > 0 else ""
            pid = str(args[1]) if len(args) > 1 else ""
            vip = args[2] if len(args) > 2 else 0
            variants = [args, [flag, pid, vip], [flag, pid]]
        elif fn == "search":
            variants = [args, [args[0], False, 1], [args[0]]]
        else:
            variants = [(), args, (False,), ({},)]
        used, data = _call_first(target, names, variants)
        if used is None:
            return _dump({
                "ok": False,
                "missing": True,
                "message": "脚本未实现 %s()：%s" % (fn, _diag(code, ns)),
            })
        return _dump({"ok": True, "data": data})
    except Exception:
        traceback.print_exc()
        return _dump({
            "ok": False,
            "message": "PY 执行失败（%d 字节，首行=%r）：%s" % (
                len(str(code)), _first_line(code), _last_line()
            ),
        })


def _last_line():
    line = traceback.format_exc(limit=3).strip().splitlines()
    return line[-1] if line else "unknown error"


def _dump(payload):
    out = json.dumps(payload, ensure_ascii=False, default=str)
    if len(out) > _MAX_OUTPUT:
        raise ValueError("返回值超过 4MB")
    return out
