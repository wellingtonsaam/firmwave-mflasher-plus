M-Flasher +
===========

Back up, restore and reinstall the firmware of an M-VAVE FM-1 or SMK-37
Pro / Elite, and install m-bootloader.

1. Let M-Flasher + reach the unit as you: install the udev rule once,
     sudo cp 70-m-flasher-plus.rules /etc/udev/rules.d/
     sudo udevadm control --reload
   and connect the unit (again, if it was connected). The rule hands the
   unit to whoever is logged in at the computer; on a system without
   systemd-logind or elogind it falls back to the plugdev group, so join
   that and log in again:
     sudo usermod -aG plugdev "$USER"
   It uses the SCSI generic driver: if there is no /dev/sg*, run
   "sudo modprobe sg", and to load it at every start,
     echo sg | sudo tee /etc/modules-load.d/m-flasher-plus.conf
2. Run ./m-flasher-plus, as you: under sudo the window cannot reach the desktop.
3. Put the unit in download mode and connect it with a USB data cable:
   - with m-bootloader: hold REC (SMK-37: SEQ REC) while switching on;
   - Felucca, SLOOP, Snoop37: hold OCT- and OCT+ for 5 s while it runs.
4. Start with "Back up the whole flash". Backups go to
   ~/Documents/M-Flasher Plus/backups.

With arguments it is a command-line tool: ./m-flasher-plus info, backup,
restore, firmware, bootloader (./m-flasher-plus --help).

m-flasher-plus.png is the icon, for a launcher of your own.

M-Flasher + is maintained by Wellington Saam. Based on M-Flasher by Julius
Vitkauskas (MIT).

More, and issues: https://github.com/wellingtonsaam/m-flasher-plus
