# Image Optimization 1.0

- Reworked cover thumbnail loading through Glide.
- Removed custom bitmap decode/display queues and scroll-time defer logic.
- Prevented repeated cover crossfade on rebound scrolling.
- Avoided focus animation on card bind so off-screen rows entering the viewport do not trigger unnecessary ViewPropertyAnimator work.

Rollback branch: backup/main-before-glide
