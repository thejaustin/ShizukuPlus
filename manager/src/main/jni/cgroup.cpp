#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>

namespace cgroup {
    bool switch_cgroup(const char *cgroup, int pid) {
        char buf[1024];
        snprintf(buf, sizeof(buf), "%s/cgroup.procs", cgroup);
        // No access() check first: open() fails the same way, without the gap between the two.
        int fd = open(buf, O_WRONLY | O_APPEND | O_CLOEXEC);
        if (fd == -1)
            return false;
        snprintf(buf, sizeof(buf), "%d\n", pid);
        ssize_t c = write(fd, buf, strlen(buf));
        close(fd);
        return c == strlen(buf);
    }
}
