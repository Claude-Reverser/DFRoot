obj-m += dfroot.o

# Size diet: this ko is written through the exploit page by page,
# so every byte counts. -Os shrinks code; -Qn/-fno-addrsig drop .comment/
# .llvm_addrsig; dropping unwind tables kills .eh_frame (unused for modules).
# Note: -fno-sanitize=kcfi is intentionally omitted — it strips the per-function
# kCFI type hash that newer kernels (e.g. SM8850) require at load time.
# .kcfi_traps is stripped post-link instead to keep older kernels happy.
CFLAGS_dfroot.o := -Os -Qn -fno-addrsig -fno-asynchronous-unwind-tables \
                   -fno-unwind-tables -fno-sanitize=cfi

all:
	$(MAKE) -C $(KDIR) M=$(CURDIR) \
		ARCH=arm64 \
		LLVM=1 \
		modules
	$(OBJCOPY) --strip-unneeded \
	           -R .comment -R .note.gnu.property \
	           -R .note.Linux -R .note.GNU-stack \
	           -R .BTF -R .BTF.base -R .llvm_addrsig \
	           -R .hyp.text -R .hyp.bss -R .hyp.rodata -R .hyp.event_ids \
	           -R .hyp.patchable_function_entries -R .hyp.data \
	           -R .kcfi_traps \
	           dfroot.ko

clean:
	$(MAKE) -C $(KDIR) M=$(CURDIR) \
		ARCH=arm64 \
		LLVM=1 \
		clean
	rm -f dfroot.lds

.PHONY: all clean
