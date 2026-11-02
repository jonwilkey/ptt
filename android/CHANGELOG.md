# Changelog

## [0.5.0](https://github.com/jonwilkey/ptt/compare/android-v0.4.0...android-v0.5.0) (2026-11-02)


### Features

* **android:** reply to check_in ([#70](https://github.com/jonwilkey/ptt/issues/70)) ([d7cddab](https://github.com/jonwilkey/ptt/commit/d7cddabd4ea0b165023483ae3542cb25d72fa148))

## [0.4.0](https://github.com/jonwilkey/ptt/compare/android-v0.3.0...android-v0.4.0) (2026-11-02)


### Features

* **android:** clip mode ([#60](https://github.com/jonwilkey/ptt/issues/60)) ([69ed3ce](https://github.com/jonwilkey/ptt/commit/69ed3ced98e19bebec544cbdddc32e734999b423))
* **android:** debug network impairment ([#61](https://github.com/jonwilkey/ptt/issues/61)) ([99c8551](https://github.com/jonwilkey/ptt/commit/99c855168ff3d4d622bd3d013a8af65dd47df603))
* **android:** receive and play clips ([#58](https://github.com/jonwilkey/ptt/issues/58)) ([d54d9d2](https://github.com/jonwilkey/ptt/commit/d54d9d23873df90411162e5222a619adc8bd1a2e))
* **android:** sender key for clip recovery ([#56](https://github.com/jonwilkey/ptt/issues/56)) ([73e0d0d](https://github.com/jonwilkey/ptt/commit/73e0d0d0f22dc8334c4aafc096e7658fb0b6b300))
* **android:** talker buffer, resume and protocol 2 ([#59](https://github.com/jonwilkey/ptt/issues/59)) ([1c56a66](https://github.com/jonwilkey/ptt/commit/1c56a66c1d24abff83cada1ad658f95084808e91))

## [0.3.0](https://github.com/jonwilkey/ptt/compare/android-v0.2.0...android-v0.3.0) (2026-11-02)


### Features

* **android:** let the family widget shrink to 3x1 ([#37](https://github.com/jonwilkey/ptt/issues/37)) ([92b6557](https://github.com/jonwilkey/ptt/commit/92b6557aa99b83ed27afa0ff1409eb858501ae2e))
* **android:** live talk screen with hold-to-talk and first-talker rule ([#47](https://github.com/jonwilkey/ptt/issues/47)) ([1975871](https://github.com/jonwilkey/ptt/commit/1975871a76fa40b6bc4893a91c0cabc368bcb182))
* **android:** Opus capture and playback for live talk ([#45](https://github.com/jonwilkey/ptt/issues/45)) ([d7f77e5](https://github.com/jonwilkey/ptt/commit/d7f77e59be0c0be87da28e5082128a5484291500))
* **android:** per-frame audio encryption ([#43](https://github.com/jonwilkey/ptt/issues/43)) ([45fd6eb](https://github.com/jonwilkey/ptt/commit/45fd6ebe0d865ec352912c705f2e8fbb46755ea2))
* **android:** volume-down talk, chirps and busy buzz ([#48](https://github.com/jonwilkey/ptt/issues/48)) ([d73f2d1](https://github.com/jonwilkey/ptt/commit/d73f2d1532355775f4afa5a9885dbe8df8cbb920))
* **android:** WebSocket client and TalkService for live talk ([#44](https://github.com/jonwilkey/ptt/issues/44)) ([2aac313](https://github.com/jonwilkey/ptt/commit/2aac313f5fd8309bc5e013c965c83505b65ec418))


### Bug Fixes

* **android:** keep the first-talker lock when a reconnect resumes the peer's transmission ([#50](https://github.com/jonwilkey/ptt/issues/50)) ([f4b98d0](https://github.com/jonwilkey/ptt/commit/f4b98d059abdb801645cbcd98e3e58a8f423e4bb))

## [0.2.0](https://github.com/jonwilkey/ptt/compare/android-v0.1.1...android-v0.2.0) (2026-10-30)


### Features

* **android:** family home-screen widget with checklist badge ([#36](https://github.com/jonwilkey/ptt/issues/36)) ([eab075f](https://github.com/jonwilkey/ptt/commit/eab075fa82d412e7e380254aa88f901c6e1b3731))
* **android:** send-and-open intent for the widget ([#35](https://github.com/jonwilkey/ptt/issues/35)) ([0be9b79](https://github.com/jonwilkey/ptt/commit/0be9b7990d59cb49d411bd24ca1e26d5aa8ad20c))
* **android:** waiting notification for sent pages ([#33](https://github.com/jonwilkey/ptt/issues/33)) ([92c5a80](https://github.com/jonwilkey/ptt/commit/92c5a80ec1df538e6f1cf0bf4a615bf61310f596))

## [0.1.1](https://github.com/jonwilkey/ptt/compare/android-v0.1.0...android-v0.1.1) (2026-10-29)


### Bug Fixes

* **android:** trust anchors for Firebase Installations; register Tink before parsing keysets ([#31](https://github.com/jonwilkey/ptt/issues/31)) ([00375f7](https://github.com/jonwilkey/ptt/commit/00375f7e1729e6be0db5053915ebf48a5dd562c9))

## 0.1.0 (2026-10-28)


### Features

* **android:** admin device tools and setup checklist ([#24](https://github.com/jonwilkey/ptt/issues/24)) ([1a32df0](https://github.com/jonwilkey/ptt/commit/1a32df00cfd51bd5baa3346c9eda83d2965ff785))
* **android:** answer pages into the talk screen ([#29](https://github.com/jonwilkey/ptt/issues/29)) ([603113b](https://github.com/jonwilkey/ptt/commit/603113b6b76016807d1050e5ae45c5d80ef2ed5f))
* **android:** app skeleton, static analysis, and CI ([#19](https://github.com/jonwilkey/ptt/issues/19)) ([d5a9470](https://github.com/jonwilkey/ptt/commit/d5a94701e5afbbe51eab8fc56556b84e9809da5d))
* **android:** crypto package with shared protocol test vectors ([#21](https://github.com/jonwilkey/ptt/issues/21)) ([f9464ae](https://github.com/jonwilkey/ptt/commit/f9464ae7af7a89e3596d2a614ae132685a8d96c1))
* **android:** enrollment, bootstrap roster, and roster sync ([#23](https://github.com/jonwilkey/ptt/issues/23)) ([9a1244a](https://github.com/jonwilkey/ptt/commit/9a1244a0601f03de3414a5f03d975684e39dfbc4))
* **android:** push handling and alerting ([#25](https://github.com/jonwilkey/ptt/issues/25)) ([ba098d3](https://github.com/jonwilkey/ptt/commit/ba098d3c9020fa59f71282a8e87dd3b1c365b30d))
* **android:** REST client, local database, and outbox ([#22](https://github.com/jonwilkey/ptt/issues/22)) ([2a81b1b](https://github.com/jonwilkey/ptt/commit/2a81b1b40a02af7e0287dc0d06b13e1e7f2bb475))
* **android:** send pages, sender progress, and Test alert ([#30](https://github.com/jonwilkey/ptt/issues/30)) ([7033791](https://github.com/jonwilkey/ptt/commit/7033791bd57db1359187a19f7ce3ba716c5ac830))


### Bug Fixes

* **deps:** raise guava and go-jose past high-severity advisories ([#28](https://github.com/jonwilkey/ptt/issues/28)) ([f6cd5f8](https://github.com/jonwilkey/ptt/commit/f6cd5f839832fca482bcd847bfb367286af785a8))
