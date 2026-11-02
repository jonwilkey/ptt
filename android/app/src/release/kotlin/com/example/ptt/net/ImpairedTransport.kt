package com.example.ptt.net

/** Release builds have no network impairment: the transport is used as is. */
fun impairTransport(inner: TalkTransport): TalkTransport = inner
