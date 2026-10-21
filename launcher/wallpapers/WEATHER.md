# Weather frames

The screensaver shows a scene's weather frame when the weather matches (Open-Meteo, see `Sky.java`):

| Weather | File name | Used for |
|---|---|---|
| Rain | `<scene>-<slot>-yagmur.jpg` | drizzle, rain, showers, and thunderstorms when there is no `firtina` frame |
| Snow | `<scene>-<slot>-kar.jpg` | snow, snow showers |
| Fog | `<scene>-<slot>-sis.jpg` | fog (falls back to `bulut`) |
| Clouds | `<scene>-<slot>-bulut.jpg` | overcast |
| Storm | `<scene>-<slot>-firtina.jpg` | thunderstorm (optional) |

`<slot>` is `sabah`, `gunduz`, `aksam` or `gece`, like the plain frames. A missing frame is fine: the plain frame shows.
Frames are 1920x1080 JPEGs; keep them under ~600 KB.

## Making them with Gemini

Open Gemini, attach the plain frame (for example `dag-gunduz.jpg`) and send the prompt for one weather. Attaching the
frame keeps the composition and the style; asking from scratch draws a different mountain. One image per message.

Shared prefix for every prompt:

> Edit this image. Keep the exact same composition, mountains, forest, framing and flat vector illustration style, and
> keep the time of day of the original. Change only the weather as described. 16:9 landscape, no text, no people, no
> watermark.

Then add one of:

- **yagmur**: Steady rain is falling: low dark grey clouds cover the whole sky, fine diagonal rain streaks in front of the
  scene, the ground and trees look wet with soft reflections. No sun or moon visible.
- **kar**: Snow is falling gently: snow covers the ground, the trees and the peaks, the light is pale and cold, the sky is
  overcast. No sun or moon visible.
- **sis**: Thick fog fills the valley: soft white haze hides the far mountains and the base of the trees, colours are
  muted and calm.
- **bulut**: The sky is fully overcast with soft grey clouds, the light is diffuse and flat, no sun or moon visible, no
  rain.

For the night frame (`dag-gece.jpg`) add: "It is night: keep the dark blue night palette."
