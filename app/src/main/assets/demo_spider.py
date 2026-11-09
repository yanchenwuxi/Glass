# -*- coding: utf-8 -*-
"""
柔光影视 · Python 源示例（Chaquopy / CPython 执行）
-----------------------------------------------------------
协议：模块顶层定义 site（元信息），并实现以下可选函数：
    home()                 -> 首页区块数组
    category(tid, page)    -> 分类内容数组
    search(keyword)        -> 搜索结果数组
    detail(id)             -> 详情对象
    player(flag, id)       -> {"url": ..., "headers": ...}
返回值为 dict/list，运行时自动 json.dumps。

可用助手：
    http_get(url, headers=None) -> str
    http_post(url, body, headers=None) -> str
    json / base64 / hashlib / time 等标准库直接 import 即可。
"""

import json
import time

site = {
    "title": "柔光 PY 示例源",
    "siteKey": "py_demo",
    "siteName": "PY 放映厅",
    "searchable": True,
}

BASE = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/"

LIBRARY = [
    {"id": "py_bbb", "title": "大雄兔 · PY 线路", "image": "BigBuckBunny", "year": "2008", "type": "动画短片", "score": 9.4},
    {"id": "py_sintel", "title": "辛特尔 · PY 线路", "image": "Sintel", "year": "2010", "type": "动画短片", "score": 9.2},
    {"id": "py_tears", "title": "钢铁之泪 · PY 线路", "image": "TearsOfSteel", "year": "2012", "type": "科幻短片", "score": 8.8},
    {"id": "py_subaru", "title": "斯巴鲁 · PY 线路", "image": "SubaruOutbackOnStreetAndDirt", "year": "2015", "type": "汽车纪录", "score": 8.2},
]


def _item(m):
    img = BASE + "images/" + m["image"] + ".jpg"
    return {
        "id": m["id"],
        "title": m["title"],
        "poster": img,
        "backdrop": img,
        "remarks": "CPython 生成",
        "year": m["year"],
        "area": "美国",
        "typeName": m["type"],
        "score": "%.1f" % m["score"],
        "siteKey": site["siteKey"],
        "siteName": site["siteName"],
    }


def home():
    items = [_item(m) for m in sorted(LIBRARY, key=lambda x: -x["score"])]
    return [{
        "title": "PY 引擎 · 正在运行",
        "subtitle": "Chaquopy CPython · 生成于 " + time.strftime("%H:%M"),
        "style": "ROW",
        "items": items,
    }]


def category(tid, page):
    return [_item(m) for m in LIBRARY]


def search(keyword):
    k = (keyword or "").strip().lower()
    return [_item(m) for m in LIBRARY if k in m["title"].lower() or k in m["type"].lower()]


def detail(ids):
    m = next((x for x in LIBRARY if x["id"] == ids), None)
    if not m:
        return None
    return {
        "description": "本条目由 Chaquopy 内嵌 CPython 执行脚本后返回。脚本可直接 import urllib、json 等标准库访问远端接口。",
        "actors": "开放版权影片",
        "director": "Blender Foundation",
        "groups": [{
            "title": "PY 线路",
            "episodes": [
                {"name": "高清 MP4", "url": BASE + m["image"] + ".mp4"},
                {"name": "HLS 自适应", "url": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"},
            ],
        }],
    }


def player(flag, ids):
    m = next((x for x in LIBRARY if x["id"] == ids), None)
    if not m:
        return None
    return {"url": BASE + m["image"] + ".mp4", "title": m["title"]}
