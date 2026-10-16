obj-m += dfroot.o

# Size diet: this ko is written through the exploit page by page,
# so every byte counts. -Os shrinks code; -Qn/-fno-addrsig drop .comment/
# .llvm_addrsig; dropping unwind tables kills .eh_frame (unused for modules);
# -fno-sanitize=cfi,kcfi suppresses .kcfi_traps
CFLAGS_dfroot.o := -Os -Qn -fno-addrsig -fno-asynchronous-unwind-tables \
                   -fno-unwind-tables -fno-sanitize=cfi \
                   $(call cc-option,-fno-sanitize=kcfi)

all:
	$(MAKE) -C $(KDIR) M=$(CURDIR) \
		ARCH=arm64 \
		LLVM=1 \
		modules

clean:
	$(MAKE) -C $(KDIR) M=$(CURDIR) \
		ARCH=arm64 \
		LLVM=1 \
		clean
	rm -f dfroot.lds

.PHONY: all clean
