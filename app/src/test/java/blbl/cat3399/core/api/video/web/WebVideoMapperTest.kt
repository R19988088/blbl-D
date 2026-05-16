package blbl.cat3399.core.api.video.web

import blbl.cat3399.core.api.BiliApiSource
import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.api.video.VideoPlayKind
import blbl.cat3399.core.api.video.VideoPlayRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WebVideoMapperTest {
    @Test
    fun parsePlayStream_shouldKeepProvidedMediaRequestProfile() {
        val mapper = WebVideoMapper(BiliApiSource.APP)
        val stream =
            mapper.parsePlayStream(
                json =
                    JSONObject(
                        """
                        {
                          "data": {
                            "timelength": 12345,
                            "dash": {
                              "duration": 12,
                              "video": [
                                {
                                  "id": 80,
                                  "codecid": 7,
                                  "base_url": "https://upos.example.com/video.m4s"
                                }
                              ],
                              "audio": [
                                {
                                  "id": 30280,
                                  "base_url": "https://upos.example.com/audio.m4s"
                                }
                              ]
                            },
                            "durl": [
                              {
                                "url": "https://upos.example.com/progressive.mp4",
                                "length": 12345
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    ),
                request = VideoPlayRequest(kind = VideoPlayKind.UGC, aid = 1L, cid = 2L),
                mediaRequestProfile = VideoMediaRequestProfile.TV,
            )

        assertEquals(VideoMediaRequestProfile.TV, stream.dash?.videos?.firstOrNull()?.mediaRequestProfile)
        assertEquals(VideoMediaRequestProfile.TV, stream.dash?.audios?.firstOrNull()?.mediaRequestProfile)
        assertEquals(VideoMediaRequestProfile.TV, stream.progressive.firstOrNull()?.mediaRequestProfile)
    }
}
