package com.github.catvod.spider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class YangshipinData {

    static final String HOST = "https://www.yangshipin.cn";
    static final String NAVIGATION_URL =
            "https://capi.yangshipin.cn/api/oms/pc/navigation/home_top_nav";
    static final String PAGE_URL = "https://capi.yangshipin.cn/api/oms/pc/page/";
    static final String PLAYER_URL = "https://player-api.yangshipin.cn/v1/player/";
    static final String OPEN_TOKEN_URL = "https://h5access.yangshipin.cn/web/open/token";
    static final String KEYGEN_URL =
            "https://s.yangshipin.cn/CCTVVideo/cctvh5-openapicore/keygen_bg.wasm";
    static final long KEYGEN_TTL = 86400000L;
    static final String EPG_URL = "https://api.cntv.cn/epg/getEpgInfoByChannelNew";
    static final String HLS_MIME = "application/x-mpegURL";
    static final long AUTO_SOURCE_MS = 8000L;
    static final long AUTO_CHOICE_MS = 300000L;
    static final long PLAY_SESSION_MS = 7200000L;
    static final long AUTO_FAILURE_MS = 30000L;
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/148.0.0.0 Safari/537.36";
    static final String YSP_APP_ID = "519748109";
    static final String APP_VERSION = "V1.0.0";
    static final String PLATFORM = "5910204";
    static final String AUTH_SALT = "n@7QKk%YeSjfw%22";
    static final String REQUEST_SALT = "0f$IVHi9Qno?G";
    static final String TICKET_KEYSTREAM =
            "6a70e9ac007ccc384c28a88dfd2211decb66494bca83bd6178f85732b2eacc9594e4e5ab720ec3a43e87b3c72b86192ff0dfea8fa8f47794d8792668438c";
    static final JsonObject GROUP_NAMES =
            JsonParser.parseString("{\"yangshi\":\"央視頻道\",\"weishi\":\"衛視頻道\"}").getAsJsonObject();
    static final String TV_APP_ID = "9f5c54c4ed0e50109b800f7e28fec205";
    static final String TV_VERSION = "1.4.1";
    static final String TV_BASE = "https://ytpaddr.cctv.cn/gsnw/";
    static final String TV_CLOUD = "https://ytpcloudws.cctv.cn/cloudps/wssapi/device/v2/";
    static final JsonObject TV_DEVICE_CODES =
            JsonParser.parseString(
                            "{\"https://ytpcloudws.cctv.cn/cloudps/wssapi/device/v2/get\":[\"0\",\"2\",\"601\"],\"https://ytpcloudws.cctv.cn/cloudps/wssapi/device/v2/register\":[\"0\",\"2\",\"694\"]}")
                    .getAsJsonObject();
    static final String TV_REPORT = "https://ytpdata.cctv.cn/das/app/data/message/single";
    static final String TV_VDN =
            "https://ytpvdn.cctv.cn/cctvmobileinf/rest/cctv/videoliveUrl/getstream";
    static final String TV_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkKeLy4ywWLSnBkwRyqYgF3HMIj05V5uuh5HjyEsZOWnu1NHu3jPQv3sr32wwQNYv5qapsNXmNgLUDHtgHZxqPQAYXltjSRc0qhcD286t62wOIHId8zXS3s1Jy4rgU4qjQWzI9rp/1sE0pMsmwTaJa4zuJ5iz8VwF8Av5oJ1k+HxY+/HLnjNlW1hmWLpuDYmkZYuAoTHa1VGeHQh9FEKI8ZcL3GTQphShUoC+Kg3P1hGUVTtCYapmzPS5lkAdwebuzwvTCfGiTErYZCnPBUSeV7BVlgjtLYIi29KvF0a8FHsJMfe/UdHcyW/RihsIYOtDQcRRpFGXyPXbVrzFJse24QIDAQAB";
    static final String APP_LIVE_URL = "https://bkliveinfo.ysp.cctv.cn/";
    static final long APP_LIVE_PLATFORM = 4330403L;
    static final String APP_LIVE_VERSION = "V8.22.1035.3031";
    static final JsonObject TV_CHANNELS =
            JsonParser.parseString(
                            "{\"600001800\":\"Live1718261577870260\",\"600001801\":\"Live1718261955077261\",\"600001803\":\"Live1718276458899270\",\"600001805\":\"Live1718276550002273\",\"600001806\":\"Live1718276603690275\",\"600001807\":\"Live1718276623932276\",\"600001809\":\"Live1718276498748271\",\"600001810\":\"Live1718276138318263\",\"600001811\":\"Live1718276575708274\",\"600001814\":\"Live1718276148119264\",\"600001815\":\"Live1718276319614267\",\"600001817\":\"Live1719473996025286\",\"600001818\":\"Live1719474204987287\",\"600001859\":\"Live1717729995180256\",\"600002264\":\"Live1767871224782105\",\"600004078\":\"Live1718276503187272\",\"600004092\":\"Live1718276412224269\",\"600014550\":\"Live1719392219423280\",\"600084704\":\"Live1719392670442283\",\"600084744\":\"Live1719392560433282\",\"600084758\":\"Live1719392779653284\",\"600084781\":\"Live1719392360336281\",\"600084782\":\"Live1719392885692285\",\"600098637\":\"Live1718276256572265\",\"600099502\":\"Live1704966749996185\",\"600156816\":\"Live1688400593818102\"}")
                    .getAsJsonObject();
    static final JsonObject XMLTV_NAMES =
            JsonParser.parseString(
                            "{\"CCTV1\":\"CCTV-1 综合\",\"CCTV2\":\"CCTV-2 财经\",\"CCTV3\":\"CCTV-3"
                                + " 综艺\",\"CCTV4\":\"CCTV-4 中文国际\",\"CCTV5\":\"CCTV-5"
                                + " 体育\",\"CCTV5+\":\"CCTV-5+ 体育赛事\",\"CCTV6\":\"CCTV-6"
                                + " 电影\",\"CCTV7\":\"CCTV-7 国防 军事\",\"CCTV8\":\"CCTV-8"
                                + " 电视剧\",\"CCTV9\":\"CCTV-9 记录\",\"CCTV10\":\"CCTV-10"
                                + " 科教\",\"CCTV11\":\"CCTV-11 戏曲\",\"CCTV12\":\"CCTV-12"
                                + " 社会与法\",\"CCTV13\":\"CCTV-13 新闻\",\"CCTV14\":\"CCTV-14"
                                + " 少儿\",\"CCTV15\":\"CCTV-15 音乐\",\"CCTV16-HD\":\"CCTV-16"
                                + " 奥林匹克\",\"CCTV16(4K）\":\"CCTV-16 奥林匹克\",\"CCTV17\":\"CCTV-17 农业"
                                + " 农村\",\"CCTV4K\":\"CCTV-4K\",\"CCTV8K\":\"CCTV8K\",\"CGTN\":\"CGTN英语\",\"CGTN法语频道\":\"CGTN法语\",\"CGTN俄语频道\":\"CGTN俄语\",\"CGTN阿拉伯语频道\":\"CGTN阿拉伯语\",\"CGTN西班牙语频道\":\"CGTN西班牙语\",\"CGTN外语纪录频道\":\"CGTN英文记录\",\"CCTV风云剧场频道\":\"CCTV-风云剧场\",\"CCTV第一剧场频道\":\"CCTV-第一剧场\",\"CCTV怀旧剧场频道\":\"CCTV-怀旧剧场\",\"CCTV世界地理频道\":\"CCTV-世界地理\",\"CCTV风云音乐频道\":\"CCTV-风云音乐\",\"CCTV兵器科技频道\":\"CCTV-兵器科技\",\"CCTV风云足球频道\":\"CCTV-央视足球\",\"CCTV高尔夫·网球频道\":\"CCTV-高尔夫网球\",\"CCTV女性时尚频道\":\"CCTV-女性时尚\",\"CCTV央视文化精品频道\":\"CCTV-央视文化精品\",\"CCTV央视台球频道\":\"CCTV-央视台球\",\"CCTV电视指南频道\":\"CCTV-电视指南\",\"CCTV卫生健康频道\":\"CCTV-卫生健康\"}")
                    .getAsJsonObject();
    static final JsonObject LOGO_NAMES =
            JsonParser.parseString(
                            "{\"CCTV16-HD\":\"CCTV16\",\"CCTV16(4K）\":\"CCTV16-4K\",\"CGTN阿拉伯语频道\":\"CGTN阿拉伯语\",\"CGTN西班牙语频道\":\"CGTN西班牙语\",\"CGTN外语纪录频道\":\"CGTN纪录\",\"CCTV高尔夫·网球频道\":\"CCTV高尔夫网球\"}")
                    .getAsJsonObject();
    static final JsonObject EPG_IDS =
            JsonParser.parseString(
                            "{\"CCTV5+\":\"cctv5plus\",\"CCTV16-HD\":\"cctv16\",\"CCTV16(4K）\":\"cctv16\",\"CCTV4K\":\"cctv4k\",\"CCTV8K\":\"cctv8k\",\"CCTV第一剧场频道\":\"diyijuchang\",\"CCTV世界地理频道\":\"shijiedili\",\"CCTV9\":\"cctvjilu\",\"CCTV14\":\"cctvchild\"}")
                    .getAsJsonObject();

    static final String OPEN_TOKEN_SECRET = "b42702bf7309a179d102f3d51b1add2fda0bc7ada64cb801";

    private YangshipinData() {

    }
}
