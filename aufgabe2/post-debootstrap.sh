#!/bin/sh

#
# Post debootstrap configuration bits for OpenStack VM images
#
# Authors: Michael Eischer <eischer@cs.fau.de>
#          Timo Hoenig <thoenig@cs.fau.de>
#          Klaus Stengel <stengel@cs.fau.de>
#

#
# Sanity checks: Must be run as root
#
HOME=/root
export HOME

if [ `id -u` -ne 0 ]; then
  echo "This script must be run as root." 1>&2
  exit 1
fi

#
# Sanity checks: Verify that we're running in the proper chroot
#
if [ -d /etc/grml ]; then
  echo "You have to enter the chroot environment first!" 1>&2
  exit 1
fi

if [ ! -e /dev/kmsg ]; then
  # can't create the dev folder out of thin air
  echo 'Access to the dev filesystem is missing'
  echo 'Run the following command OUTSIDE the chroot environment:'
  echo 'mount -o bind /dev /mnt/dev/'
  exit 1
fi
# Check for proc, sys and devpts file system
if [ ! -e /proc/cpuinfo ]; then
  mount -t proc proc /proc
  echo 'Mounted proc file system. Use umount /proc to unmount.'
fi
if [ ! -e /sys/kernel ]; then
  mount -t sysfs sysfs /sys
  echo 'Mounted sys file system. Use umount /sys to unmount.'
fi
if [ ! -e /dev/pts/ptmx ]; then
  # make devpts (terminal multiplexer) writeable by tty group
  mount -t devpts -o gid=5,mode=620 devpts /dev/pts
  echo 'Mounted pts file system. Use umount /dev/pts to unmount.'
fi


#
# Speedup installation
#
# Let's assume the vm doesn't loose power during installation...
mount -o remount,nobarrier /

#
# Setup /etc/apt/sources.list with sources for Debian jessie
#
echo 'Setting up /etc/apt/sources.list'
cat >/etc/apt/sources.list <<'EOF'
deb http://ftp.fau.de/debian bookworm main contrib non-free
deb http://ftp.fau.de/debian bookworm-updates main contrib non-free
deb http://security.debian.org bookworm-security main contrib non-free
EOF

#
# Install some system packages
#
apt-get update
apt-get upgrade -y
apt-get dist-upgrade -y
# make sure grub doesn't bother the user with questions
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" \
  locales dnsutils resolvconf sudo psmisc curl unattended-upgrades \
  linux-image-amd64 grub-pc openssh-server cloud-utils
# some convenience packages
tasksel install standard
# reduce vm image size a bit
apt-get clean

#
# Grow root file system on boot
# FIXME: replace with systemd-growfs once it supports partition grow
#
cat > /etc/systemd/system/growroot.service <<'EOF'
[Unit]
Description=Extend root partition and resize ext4 file system
After=local-fs.target
Wants=local-fs.target

[Service]
Environment=ROOT_DISK=/dev/vda
Environment=ROOT_PARTITION=1
ExecStart=/bin/bash -c "/usr/bin/growpart -N \${ROOT_DISK} \${ROOT_PARTITION} && /usr/bin/growpart \${ROOT_DISK} \${ROOT_PARTITION} || exit 0"
ExecStop=/bin/bash -c "/sbin/resize2fs \${ROOT_DISK}\${ROOT_PARTITION} || exit 0"
Type=oneshot

[Install]
WantedBy=multi-user.target
EOF
systemctl enable growroot.service

#
# Setup locales
#
cat >/etc/locale.gen <<EOF
en_US.UTF-8 UTF-8
en_US ISO-8859-1
en_US.ISO-8859-15 ISO-8859-15
de_DE.UTF-8 UTF-8
de_DE ISO-8859-1
de_DE@euro ISO-8859-15
EOF

locale-gen

#
# Setup /etc/fstab for KVM environments
#
echo 'Setting up /etc/fstab'
cat >/etc/fstab <<EOF
# Operating system image
/dev/vda1 /    ext4  noatime 0 1

# Swap space
/dev/vdb none swap  sw      0 0
EOF

#
# Fixup logging, OpenStack uses /dev/ttyS0
# Disable predictable network interface names, the vm has only one network interface
# Swap consoles for debugging; initramdisk output is printed on the last console!
#
sed -i -e "s/^GRUB_CMDLINE_LINUX_DEFAULT.*/GRUB_CMDLINE_LINUX_DEFAULT=\"net.ifnames=0 console=tty0 console=ttyS0,115200n8\"/" \
  /etc/default/grub

#
# Make the vm bootable
#
grub-install /dev/vdb
update-grub

#
# Setup /etc/network/interfaces for networking (DHCP)
#
echo 'Setting up /etc/network/interfaces'
cat >/etc/network/interfaces <<'EOF'
# See man 5 interfaces for documentation
auto lo eth0
iface lo inet loopback
iface eth0 inet dhcp
EOF

#
# Disable IPv6
#
echo 'Setting up /etc/sysctl.conf'
cat >/etc/sysctl.d/no-ipv6 <<'EOF'
# Disable IPv6
net.ipv6.conf.all.disable_ipv6 = 1
net.ipv6.conf.default.disable_ipv6 = 1
net.ipv6.conf.lo.disable_ipv6 = 1
EOF

#
# Systemd prevents services from starting inside the chroot
# No need for a policy script in /usr/sbin/policy.d
#

#
# Add start script which sets the hostname (note: EOX wraps EOF)
#     and make script executable (mode 755)
#
echo 'Setting up /etc/network/if-up.d/01set-hostname'
cat >/etc/network/if-up.d/01set-hostname <<'EOX'
#! /bin/bash
# Set hostname when primary device is ready

# Don't bother if not primary interface
if [ "$IFACE" != eth0 ]; then exit 0; fi

inet_addr=`wget -q -O - http://169.254.169.254/latest/meta-data/public-ipv4`

# See if it looks like a valid IP
printf '%s\n' "$inet_addr" | grep -q '^[0-9]\+\.[0-9]\+\.[0-9]\+\.[0-9]\+$'
if [ "$?" -eq 0 ]; then
	new_fqdn=`host -4 "$inet_addr"`
	if [ "$?" -ne 0 ]; then
		new_fqdn=`printf '%s\n' "$inet_addr" | tr . -`
		new_fqdn="i4mw-${new_fqdn}.localdomain"
	else
		new_fqdn=`printf '%s\n' "$new_fqdn" | awk 'NF>1{print $NF}' | sed -e 's/\.$//'`
	fi
else
	inet_addr=`ip addr show dev $IFACE | grep '^    inet ' | sed -e 's/^    inet //' -e 's?/.*??'`
	new_fqdn=`printf '%s\n' "$inet_addr" | cut -f 4 -d.`
	new_fqdn="i4mw-${new_fqdn}.localdomain"
fi

new_hostname=`printf '%s\n' "$new_fqdn" | cut -f 1 -d.`

printf '%s\n' "$new_hostname" >/etc/hostname
sysctl kernel.hostname="$new_hostname" >/dev/null

cat >/etc/hosts <<EOF
127.0.0.1 localhost localhost.localdomain
$inet_addr $new_fqdn $new_hostname
EOF
EOX
chmod 755 /etc/network/if-up.d/01set-hostname

#
# Load ssh keys from the metadata service
#
cat >/etc/systemd/system/insert-ssh-key.service <<'EOF'
[Unit]
After=network.target

[Service]
ExecStart=/usr/local/bin/insert-ssh-key

[Install]
WantedBy=multi-user.target
EOF

cat >/usr/local/bin/insert-ssh-key <<'EOF'
#!/bin/bash
meta_url='http://169.254.169.254/latest/meta-data'
dest_user='cloud'

user_idg=`getent passwd "$dest_user" | cut -f 3-4 -d :`
user_home=`getent passwd "$dest_user" | cut -f 6 -d :`
tmpdir=`mktemp -d`
pklist="$tmpdir/pks"
curkey="$tmpdir/key"
authkeys="$user_home/.ssh/authorized_keys"

if [ `printf '%s\n' "$tmpdir" | cut -c 1-5` != "/tmp/" ]; then
	echo "Unexpected tempdir $tmpdir" >&2
	exit 1
fi

if [ ! -e "$user_home/.ssh" ]; then
	mkdir "$user_home/.ssh"
	chown "$user_idg" "$user_home/.ssh"
	chmod 755 "$user_home/.ssh"
fi

if [ ! -e "$authkeys" ]; then
	touch "$authkeys"
	chown "$user_idg" "$authkeys"
	chmod 644 "$authkeys"
fi

echo "Fetching i4cloud SSH Keys..." >&2
wget -q -O "$pklist" "$meta_url/public-keys"
if [ $? -ne 0 ]; then
	echo "Failed to fetch list of SSH keys to install" >&2
	exit 1
fi

echo >>"$pklist"
cut -f 1 -d= <"$pklist" >"${pklist}_"
mv "${pklist}_" "$pklist"

keycount=0
while read -r val; do
	if [ -z "$val" ]; then continue; fi
	wget -q -O "$curkey" "$meta_url/public-keys/$val/openssh-key"
	if [ $? -ne 0 ]; then
		echo "Failed to fetch public key with index $val" >&2
		continue
	fi
	printf '# Imported from cloud (%s)\n' "$val" >>"$authkeys"
	cat "$curkey" >>"$authkeys"
	echo >>"$authkeys"
	keycount=$((keycount+1));
	rm -f "$curkey"
done <"$pklist"

rm -rf "$tmpdir"

if [ "$keycount" -eq 0 ]; then
	echo "WARNING: No SSH keys to import from i4cloud found." >&2
else
	echo "$keycount key(s) installed"
fi
EOF
chmod 755 /usr/local/bin/insert-ssh-key

systemctl enable insert-ssh-key.service

#
# Configure SSH. Just disable password authentication and login as root
#
echo 'Setting up /etc/ssh/sshd_config'
sed -i -e "s/^.*PermitRootLogin.*/PermitRootLogin no/g" \
  -e "s/^.*PasswordAuthentication.*/PasswordAuthentication no/g" \
  /etc/ssh/sshd_config

#
# Start i4mw java service
#
cat >/etc/systemd/system/i4mw-service.service <<'EOF'
[Unit]
After=network.target

[Service]
ExecStart=/usr/local/bin/i4mw-service
# Show messages on console and syslog
StandardOutput=journal+console
StandardError=journal+console

[Install]
WantedBy=multi-user.target
EOF

cat >/usr/local/bin/i4mw-service <<'EOF'
#!/bin/bash
#
# I4MW Init Script for OpenStack
#
# Author: Timo Hönig <thoenig@cs.fau.de>
#         Michael Eischer <eischer@cs.fau.de>
#

I4MW_BIN="/proj/bin"
I4MW_LIB="/proj/lib"

# Waiting for floating ip
I4MW_ADDRESS=""
while [[ -z "$I4MW_ADDRESS" ]] ; do
  echo "Checking for floating ip..."
  I4MW_ADDRESS="`curl -s -f http://169.254.169.254/latest/meta-data/local-ipv4`"
  sleep 5
done

# print user data
curl -s -f http://169.254.169.254/latest/user-data
echo "" # ensure newline
if [[ $? != 0 ]] ; then
    echo "User-data is empty, aborting."
    exit 1
fi

# Parse OpenStack user data
OIFS=$IFS
IFS=';'
for x in `curl -s -f http://169.254.169.254/latest/user-data`; do
    v=`echo I4MW_$x |cut -f 1 -d "="|tr [:lower:] [:upper:]`=`echo $x|cut -f 2- -d "="`
    export `eval echo \$"${v}"`
done
IFS=$OIFS

# Environment
export |grep I4MW

# Check for hook script
if [ -n "${I4MW_HOOK}" ] ; then
  echo "Fetching '$I4MW_HOOK'..."
  wget -S -O /tmp/hook.sh $I4MW_HOOK
  echo "Starting hook script."
  /bin/bash /tmp/hook.sh
else
  echo "No hook script supplied."
fi

# Check for group name
if [ -n "${I4MW_GROUP}" ] ; then
  echo "Group name is: '$I4MW_GROUP'."
else
  echo "Group name not available, aborting."
  exit 1
fi

# Check for jar name
if [ -n "${I4MW_JAR}" ] ; then
  echo "Jar name is: '$I4MW_JAR'."
else
  echo "Jar name not available, aborting."
  exit 1
fi

# Check for additional CLASSPATH information
if [ -n "${I4MW_CLASSPATH}" ] ; then
  echo "Additional CLASSPATH information: '$I4MW_CLASSPATH'."
else
  I4MW_CLASSPATH='/proj/lib/jaxrs-ri-2.45/*:/proj/lib/tika/*'
  echo "Using default CLASSPATH '$I4MW_CLASSPATH'."
fi

# Check for Java defines
if [ -n "${I4MW_JAVA_DEFINES}" ] ; then
  echo "Java defines: '$I4MW_JAVA_DEFINES'."
else
  echo "No Java defines supplied."
fi

# Set I4MW_URL
I4MW_URL="http://$I4MW_GROUP.s3.eu-west-1.amazonaws.com/$I4MW_JAR"

# Create target directory
mkdir -p $I4MW_BIN

# Fetch jar file from S3
echo "Fetching '$I4MW_URL'..."
wget -S -O $I4MW_BIN/$I4MW_JAR $I4MW_URL
if [ "$?" -ne "0" ] ; then
  echo "Could not retrieve '$I4MW_URL', aborting."
  exit 1
fi

# Check for entrypoint
if [ -n "${I4MW_CLASS}" ] ; then
  echo "Classname of entrypoint for execution of $I4MW_JAR: '$I4MW_CLASS'."
else
  echo "No classname of entrypoint for execution of $I4MW_JAR supplied."
fi

# Check for parameters
if [ -n "${I4MW_PARAMETERS}" ] ; then
  echo "Parameters for execution of $I4MW_JAR: '$I4MW_PARAMETERS'."
else
  echo "No additional parameters for execution of $I4MW_JAR supplied."
fi

echo "Starting '$I4MW_BIN/$I4MW_JAR'..."
echo "$ java -cp $I4MW_BIN/$I4MW_JAR:$I4MW_CLASSPATH -D$I4MW_JAVA_DEFINES $I4MW_CLASS $I4MW_PARAMETERS"
exec java -cp $I4MW_BIN/$I4MW_JAR:$I4MW_CLASSPATH -D$I4MW_JAVA_DEFINES $I4MW_CLASS $I4MW_PARAMETERS
EOF
chmod +x /usr/local/bin/i4mw-service

systemctl enable i4mw-service.service

#
# Create cloud user if not already exits
#

getent passwd cloud >/dev/null 2>&1
if [ "$?" -eq 0 ]; then
  echo "User 'cloud' already exists. Skipped user setup."
else
  echo 'Creating user cloud in group admin'
  useradd cloud -g users -G sudo -s /bin/bash -m
  # Allow passwordless sudo
  cat >/etc/sudoers.d/i4cloud <<EOF
cloud    ALL=(ALL:ALL) NOPASSWD: ALL
EOF
fi

cat <<'EOF'
Post debootstrap configuration done.

Please set a password for user 'cloud' by running "passwd cloud" now.
(NOTE: Do _NOT_ use your CIP or RRZE password. Some simple password is OK.)'
EOF
