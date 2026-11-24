/* pcctool: per-display Qualcomm PCC control for Retroid Pocket Duo (needs root).
 *
 * PCC (Polynomial Colour Correction) is the display hardware's colour matrix. Qualcomm's vendor library
 * libsdm-disp-vndapis.so can set it per display; this tool loads that library and calls it.
 * Display 0 = top screen, display 3 = bottom screen. The hardware forgets the values when the screen sleeps,
 * which is why DuoTune saves permanent colours as calibration files instead.
 *
 * Built without the Android NDK's libc headers (see build.sh), so the few libc functions used are declared here.
 */
typedef unsigned long u64; typedef unsigned int u32;
int dprintf(int, const char*, ...); double strtod(const char*, char**); long strtol(const char*, char**, int);
int strcmp(const char*, const char*); void *memset(void*, int, unsigned long);
void *dlopen(const char*, int); void *dlsym(void*, const char*); char *dlerror(void);
int open(const char*, int, ...); long read(int, void*, unsigned long); long write(int, const void*, unsigned long);
int close(int); int usleep(unsigned int); int rename(const char*, const char*);
int fork(void); int execve(const char*, char *const*, char *const*); int setsid(void); int kill(int, int);
int getpid(void); int waitpid(int, int*, int); int dup2(int, int); void _exit(int);
void *opendir(const char*); void *readdir(void*); int closedir(void*); int unlink(const char*);
int fchmod(int, unsigned); int fchown(int, unsigned, unsigned); int fsetxattr(int, const char*, const void*, unsigned long, int);
long readlink(const char*, char*, unsigned long); unsigned long strlen(const char*); int strncmp(const char*, const char*, unsigned long);
int snprintf(char*, unsigned long, const char*, ...);

/* open() flags (arm64 Linux; HOST_TEST builds use the PC's own values) */
#ifdef HOST_TEST
#include <fcntl.h>
#else
#define O_WRONLY 1
#define O_CREAT 0100
#define O_TRUNC 01000
#define O_NONBLOCK 04000
#define O_DIRECTORY 040000
#define O_NOFOLLOW 0100000
#endif
/* bionic arm64 struct dirent: d_ino(8) d_off(8) d_reclen(2) d_type(1) d_name[256] */
#define DIRENT_NAME(d) ((const char*)(d) + 19)
#define DT_REG 8
#define DIRENT_TYPE(d) (*((const unsigned char*)(d) + 18))

typedef int (*init_fn)(u64*, u32);
typedef int (*getpcc_fn)(u64, u32, u32*, void*);
typedef int (*setpcc_fn)(u64, u32, u32, void*);
typedef void (*initdrv_fn)(const char*);

/* The PCC config: 34 doubles. Slot 2 = red gain, 14 = green gain, 26 = blue gain; the rest stay 0. */
static union { unsigned char b[272]; u64 q[34]; double d[34]; } cfg;

static int usage(void) {
  dprintf(1, "usage:\n  pcctool get <display>\n  pcctool setraw <display> <enable> <34 doubles: slot0..slot33>\n  pcctool set <display> <r> <g> <b>   (gains 0.0-1.0, top=0 bottom=3)\n  pcctool off <display>\n  pcctool apply <r0> <g0> <b0> <r3> <g3> <b3>\n  pcctool watch <values file> <heartbeat file>\n  pcctool run   (what duotune.sh starts: does the action DuoTune asked for)\n  pcctool scan 0\n");
  return 2;
}


/* ===================================================================================================
 * run: the ONLY thing duotune.sh starts as root. It does one of three fixed actions and nothing else.
 * DuoTune writes the wanted action ("live", "save" or "remove") into its private folder; this binary sits in
 * the app's read-only install folder (from the signed APK), so no app can change what these actions do.
 * =================================================================================================== */
#ifndef TEST_F
#define TEST_F "/data/user/0/com.duotune/files"
#define TEST_D "/data/vendor/display"
#endif
static const char *F = TEST_F;                                  /* DuoTune's private folder */
static const char *D = TEST_D;                                  /* where the display service looks for overrides */
static const char *LABEL = "u:object_r:vendor_display_vendor_data_file:s0";
static const char *PREFIX = "qdcm_calib_data_";

static int starts(const char *s, const char *p) { return strncmp(s, p, strlen(p)) == 0; }
static int ends(const char *s, const char *e) { unsigned long a = strlen(s), b = strlen(e); return a >= b && !strcmp(s + a - b, e); }

/* A calibration file name we are willing to touch: qdcm_calib_data_<letters, digits, _ . -> .json, no paths. */
static int good_name(const char *n) {
  unsigned long len = strlen(n);
  if (len > 120 || !starts(n, PREFIX) || !ends(n, ".json")) return 0;
  for (unsigned long i = 0; i < len; i++) {
    char c = n[i];
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) return 0;
  }
  return 1;
}

/* Writes "text" to a file in DuoTune's folder (never through a link) and makes it readable for the app. */
static void put(const char *name, const char *text) {
  char p[256]; snprintf(p, sizeof p, "%s/%s", F, name);
  int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0644);
  if (fd < 0) return;
  write(fd, text, strlen(text)); fchmod(fd, 0644); close(fd);
}

/* Stops any running live helper ("libpcctool.so watch ..."), except ourselves. */
static void stop_helpers(void) {
  void *dir = opendir("/proc"); if (!dir) return;
  int me = getpid(); void *e;
  while ((e = readdir(dir))) {
    const char *n = DIRENT_NAME(e); int pid = 0;
    for (const char *c = n; *c; c++) { if (*c < '0' || *c > '9') { pid = 0; break; } pid = pid * 10 + (*c - '0'); }
    if (pid <= 0 || pid == me) continue;
    char p[64], cmd[512]; snprintf(p, sizeof p, "/proc/%d/cmdline", pid);
    int fd = open(p, 0); if (fd < 0) continue;
    long k = read(fd, cmd, sizeof cmd - 1); close(fd);
    if (k <= 0) continue; cmd[k] = 0;
    unsigned long a = strlen(cmd);                     /* argv[0], then argv[1] after the NUL */
    if (ends(cmd, "libpcctool.so") && (long)a + 1 < k && !strcmp(cmd + a + 1, "watch")) kill(pid, 9);
  }
  closedir(dir);
}

/* Runs a fixed shell command (no input from any file) and waits for it. */
static void sh(const char *cmd, char **envp) {
  int pid = fork();
  if (pid == 0) { char *a[] = {"sh", "-c", (char*)cmd, 0}; execve("/system/bin/sh", a, envp); _exit(127); }
  if (pid > 0) { int st; waitpid(pid, &st, 0); }
}

static void reboot_now(char **envp) {
#ifdef HOST_TEST
  dprintf(1, "[test] would reboot now\n"); return;
#endif
  char *a[] = {"sh", "-c", "sleep 1; svc power reboot || reboot", 0};
  execve("/system/bin/sh", a, envp);
}

/* Deletes DuoTune's calibration files from /data/vendor/display. Returns how many. */
static int remove_saved(void) {
  void *dir = opendir(D); if (!dir) return -1;
  int n = 0; void *e; char p[256];
  while ((e = readdir(dir))) {
    const char *name = DIRENT_NAME(e);
    if (!good_name(name)) continue;
    snprintf(p, sizeof p, "%s/%s", D, name);
    if (unlink(p) == 0) n++;
  }
  closedir(dir);
  return n;
}

/* Copies one prepared file from files/out into /data/vendor/display with the owner, mode and label it needs. */
static int install(const char *name) {
  char src[256], dst[256], buf[65536];
  snprintf(src, sizeof src, "%s/out/%s", F, name);
  snprintf(dst, sizeof dst, "%s/%s", D, name);
  int in = open(src, O_NOFOLLOW | O_NONBLOCK);
  if (in < 0) return 0;
  long k = read(in, buf, sizeof buf), total = 0;
  if (k <= 0 || buf[0] != '{') { close(in); return 0; }          /* must look like a JSON calibration file */
  int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0644);
  if (out < 0) { close(in); return 0; }
  int ok = 1;
  while (k > 0) {
    total += k;
    if (total > 16L * 1024 * 1024 || write(out, buf, k) != k) { ok = 0; break; }   /* 16 MB limit */
    k = read(in, buf, sizeof buf);
  }
  close(in);
  if (ok) {
    fchmod(out, 0644);
    fchown(out, 1000, 1003);                                      /* system:graphics */
    fsetxattr(out, "security.selinux", LABEL, strlen(LABEL) + 1, 0);
  }
  close(out);
  if (!ok) unlink(dst);
  return ok;
}

static int do_run(char **envp) {
  char p[256], act[16]; snprintf(p, sizeof p, "%s/action", F);
  int fd = open(p, O_NOFOLLOW | O_NONBLOCK);
  long n = fd >= 0 ? read(fd, act, sizeof act - 1) : -1;
  if (fd >= 0) close(fd);
  if (n <= 0) { dprintf(1, "DuoTune: nothing to do (open DuoTune first)\n"); return 1; }
  act[n] = 0;
  for (long i = 0; i < n; i++) if (act[i] == '\n' || act[i] == ' ') act[i] = 0;

  if (!strcmp(act, "live")) {
    /* Start the live helper in the background, then bring DuoTune back to the front. */
    stop_helpers();
    char self[512]; long l = readlink("/proc/self/exe", self, sizeof self - 1);
    if (l <= 0) { put("result.txt", "error\n"); return 1; }
    self[l] = 0;
    char live[256], alive[256], log[256];
    snprintf(live, sizeof live, "%s/live.txt", F); snprintf(alive, sizeof alive, "%s/alive", F);
    snprintf(log, sizeof log, "%s/helper.log", F);
    unlink(alive);
    int pid = fork();
    if (pid == 0) {
      setsid();                                                   /* keep running after the script returns */
      int nul = open("/dev/null", 0), lg = open(log, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0644);
      if (nul >= 0) dup2(nul, 0);
      if (lg >= 0) { dup2(lg, 1); dup2(lg, 2); }
      char *env[] = {"LD_LIBRARY_PATH=/vendor/lib64", "PATH=/system/bin:/vendor/bin", 0};
      char *a[] = {"libpcctool.so", "watch", live, alive, 0};
      execve(self, a, env);
      _exit(127);
    }
    put("result.txt", "live\n");
#ifndef HOST_TEST
    sh("sleep 1; am start --activity-reorder-to-front -n com.duotune/.MainActivity >/dev/null 2>&1", envp);
#endif
    dprintf(1, "DuoTune: live tuning started\n");
    return 0;
  }

  if (!strcmp(act, "save")) {
    /* Replace any earlier saved colours with the files DuoTune prepared, then reboot so they load. */
    stop_helpers();
    char od[256]; snprintf(od, sizeof od, "%s/out", F);
    void *dir = opendir(od);
    if (!dir) { put("result.txt", "error no files\n"); return 1; }
    remove_saved();
    int n = 0; void *e;
    while ((e = readdir(dir))) {
      const char *name = DIRENT_NAME(e);
      if (DIRENT_TYPE(e) == DT_REG && good_name(name)) n += install(name);
    }
    closedir(dir);
    if (n == 0) { put("result.txt", "error no files\n"); return 1; }
    char msg[32]; snprintf(msg, sizeof msg, "saved %d\n", n);
    put("result.txt", msg);
    dprintf(1, "DuoTune: saved %d calibration files, rebooting\n", n);
    reboot_now(envp);
    return 0;
  }

  if (!strcmp(act, "remove")) {
    /* Delete DuoTune's files; the Duo falls back to its own calibration in /vendor after the reboot. */
    stop_helpers();
    remove_saved();
    put("result.txt", "removed\n");
    dprintf(1, "DuoTune: saved colours removed, rebooting\n");
    reboot_now(envp);
    return 0;
  }

  dprintf(1, "DuoTune: unknown action\n");
  return 1;
}

int main(int argc, char **argv, char **envp) {
  if (argc == 2 && !strcmp(argv[1], "run")) return do_run(envp);   /* no vendor library needed for this */
  if (argc < 3) return usage();
  /* Load the vendor display library (RTLD_NOW) and look up the three calls we need. */
  void *bnd = dlopen("libbinder.so", 2);
  void *h = dlopen("libsdm-disp-vndapis.so", 2);
  if (!h) { dprintf(1, "dlopen failed: %s\n", dlerror()); return 1; }
  init_fn init = (init_fn)dlsym(h, "disp_api_init");
  getpcc_fn getp = (getpcc_fn)dlsym(h, "disp_api_get_global_pcc_config");
  setpcc_fn setp = (setpcc_fn)dlsym(h, "disp_api_set_global_pcc_config");
  if (!init || !getp || !setp) { dprintf(1, "dlsym failed\n"); return 1; }
  u64 ctx = 0; int rc = init(&ctx, 0);
  if (rc) { dprintf(1, "init failed rc=%d\n", rc); return 1; }
  u32 disp = (u32)strtol(argv[2], 0, 0);

  /* scan: list which display ids answer (used to find 0 and 3). */
  if (!strcmp(argv[1], "scan")) {
    for (u32 d = 0; d <= 8; d++) {
      u32 en = 0; memset(&cfg, 0, sizeof cfg);
      int r = getp(ctx, d, &en, &cfg);
      int nz = 0; for (int i = 0; i < 34; i++) if (cfg.q[i]) nz++;
      dprintf(1, "display %u: rc=%d enable=%u nonzero_slots=%d\n", d, r, en, nz);
    }
    return 0;
  }
  /* get <display>: print the current config. */
  if (!strcmp(argv[1], "get")) {
    u32 en = 0; memset(&cfg, 0, sizeof cfg);
    rc = getp(ctx, disp, &en, &cfg);
    dprintf(1, "get display=%u rc=%d enable=%u\n", disp, rc, en);
    for (int i = 0; i < 34; i++) dprintf(1, "slot %2d  0x%016lx  %f\n", i, cfg.q[i], cfg.d[i]);
    return rc ? 1 : 0;
  }
  /* setraw: write all 34 slots (for experiments). */
  if (!strcmp(argv[1], "setraw")) {
    if (argc < 4 + 34) return usage();
    u32 en = (u32)strtol(argv[3], 0, 0);
    for (int i = 0; i < 34; i++) cfg.d[i] = strtod(argv[4 + i], 0);
    rc = setp(ctx, disp, en, &cfg);
    dprintf(1, "setraw display=%u enable=%u rc=%d\n", disp, en, rc);
    return rc ? 1 : 0;
  }
  /* set <display> <r> <g> <b>: one screen. */
  if (!strcmp(argv[1], "set")) {
    if (argc < 6) return usage();
    double r = strtod(argv[3], 0), g = strtod(argv[4], 0), b = strtod(argv[5], 0);
    if (r < 0 || r > 1 || g < 0 || g > 1 || b < 0 || b > 1) { dprintf(1, "values must be between 0.0 and 1.0\n"); return 2; }
    memset(&cfg, 0, sizeof cfg);
    cfg.d[2] = r; cfg.d[14] = g; cfg.d[26] = b;   /* R.r, G.g, B.b gains */
    rc = setp(ctx, disp, 1, &cfg);
    dprintf(1, "display %u: R=%.3f G=%.3f B=%.3f rc=%d\n", disp, r, g, b, rc);
    return rc ? 1 : 0;
  }
  if (!strcmp(argv[1], "watch")) {
    /* watch <values file> <heartbeat file>: helper for live tuning without Magisk.
       Reads exactly six gains (top R G B, bottom R G B) from a file only the app can write,
       applies them whenever they change, touches the heartbeat about once a second.
       Exits when the file starts with 'q', or after 10 minutes without changes. */
    if (argc < 4) return usage();
    const char *vf = argv[2], *hb = argv[3];
    char last[256], buf[256]; int lastn = -1;
    unsigned idle = 0, tick = 0, beat = 0;
    dprintf(1, "helper started\n");
    for (;;) {
      int n = -1, fd = open(vf, O_NOFOLLOW | O_NONBLOCK);
      if (fd >= 0) { n = (int)read(fd, buf, sizeof buf - 1); close(fd); }
      if (n > 0) {
        buf[n] = 0;
        if (buf[0] == 'q') { dprintf(1, "quit requested\n"); break; }
        int same = (n == lastn);
        for (int i = 0; same && i < n; i++) if (buf[i] != last[i]) same = 0;
        if (!same) {
          double v[6]; char *p = buf, *e; int ok = 1;
          for (int k = 0; k < 6; k++) {
            v[k] = strtod(p, &e);
            if (e == p || v[k] < 0 || v[k] > 1) { ok = 0; break; }
            p = e;
          }
          if (ok) {
            u32 ids[2] = {0, 3};            /* top, bottom; enable=0 when a screen has no correction */
            for (int k = 0; k < 2; k++) {
              memset(&cfg, 0, sizeof cfg);
              cfg.d[2] = v[3*k]; cfg.d[14] = v[3*k+1]; cfg.d[26] = v[3*k+2];
              int en = !(v[3*k] == 1.0 && v[3*k+1] == 1.0 && v[3*k+2] == 1.0);
              rc = setp(ctx, ids[k], en, &cfg);
              if (rc) dprintf(1, "display %u: rc=%d\n", ids[k], rc);
            }
          } else dprintf(1, "ignored invalid values\n");
          for (int i = 0; i < n; i++) last[i] = buf[i];
          lastn = n; idle = 0;
        }
      }
      if (tick % 20 == 0) {               /* heartbeat about once a second */
        int h = open(hb, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0644);
        if (h >= 0) { char c = '0' + (char)(beat++ % 10); write(h, &c, 1); close(h); }
      }
      tick++;
      if (++idle > 12000) { dprintf(1, "idle for 10 minutes, stopping\n"); break; }
      usleep(50000);
    }
    return 0;
  }
  if (!strcmp(argv[1], "apply")) {
    /* apply <r0> <g0> <b0> <r3> <g3> <b3> : both screens in one call (argv[2] is r0) */
    if (argc < 8) return usage();
    u32 ids[2] = {0, 3}; int bad = 0;
    for (int k = 0; k < 2; k++) {
      double r = strtod(argv[2 + 3*k], 0), g = strtod(argv[3 + 3*k], 0), b = strtod(argv[4 + 3*k], 0);
      if (r < 0 || r > 1 || g < 0 || g > 1 || b < 0 || b > 1) { dprintf(1, "values must be between 0.0 and 1.0\n"); return 2; }
      memset(&cfg, 0, sizeof cfg);
      cfg.d[2] = r; cfg.d[14] = g; cfg.d[26] = b;
      int en = !(r == 1.0 && g == 1.0 && b == 1.0);
      rc = setp(ctx, ids[k], en, &cfg);
      dprintf(1, "display %u: R=%.3f G=%.3f B=%.3f enable=%d rc=%d\n", ids[k], r, g, b, en, rc);
      if (rc) bad = 1;
    }
    return bad;
  }
  /* off <display>: disable correction on one screen. */
  if (!strcmp(argv[1], "off")) {
    memset(&cfg, 0, sizeof cfg);
    rc = setp(ctx, disp, 0, &cfg);
    dprintf(1, "display %u: correction off rc=%d\n", disp, rc);
    return rc ? 1 : 0;
  }
  return usage();
}
