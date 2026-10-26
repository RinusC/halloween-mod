# VR

Разделы дизайна по порядку (старые сверху; поздние правят ранние).

- [[Дизайн/VR/123 VR через Vivecraft (подготовка)|123 VR через Vivecraft (подготовка)]]

## VR в разделах других тем
- [[Дизайн/Правки и прочее/122 Векна правки — Глаза, притягивание у стены, прицел, Якорь к алтарю|122 Векна правки — Глаза, притягивание у стены, прицел, Якорь к алтарю]]
- [[Дизайн/Правки и прочее/124 Погоня отрыв, музыка слоями, препятствия|124 Погоня отрыв, музыка слоями, препятствия]]
- [[Дизайн/Правки и прочее/125 Выжившие навыки героев, метки, побочные задания|125 Выжившие навыки героев, метки, побочные задания]]

## Код VR
- **Vr** ([[Код/dev.halloween|dev.halloween]]) — Vivecraft bridge for both sides.
- **ClientVr** ([[Код/dev.halloween.client|dev.halloween.client]]) — The local headset.
- **VrHands** ([[Код/dev.halloween.client|dev.halloween.client]]) — The hands in the headset: what is done by moving them, not by buttons.
- **VrKeys** ([[Код/dev.halloween.client|dev.halloween.client]]) — Buttons in the headset.
- **VrMarks** ([[Код/dev.halloween.client|dev.halloween.client]]) — The HUD's marks on the world, in the headset.
- **VrShots** ([[Код/dev.halloween.client|dev.halloween.client]]) — The scenes in the headset.
- **VrWrist** ([[Код/dev.halloween.client|dev.halloween.client]]) — The role's buttons on the wrist, in the headset.
- **VrFogMixin** ([[Код/dev.halloween.client.mixin|dev.halloween.client.mixin]]) — Black between the scenes' shots in the headset: the fog closes in to the eyes and goes black (see VrShots).
- **VrWristClientTest** ([[Код/dev.halloween.test|dev.halloween.test]]) — The role's buttons on the wrist (client.VrWrist), drawn as the headset would show them over the free hand, here hung before the camera (the test client has no Vivecraft): as they come up, one hovered by the other hand.

## Заметки
- [[shaders-plan]]
- [[shaders-recon]]
