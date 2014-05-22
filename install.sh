#wget https://raw.githubusercontent.com/puniverse/CascadingFailureExample/master/install.sh
#chmod+x and run

# install jdk8
sudo apt-get update
wget --no-cookies --no-check-certificate --header "Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie" "http://download.oracle.com/otn-pub/java/jdk/8-b132/jdk-8-linux-x64.tar.gz"
sudo mkdir /usr/local/java
sudo mv jdk-8-linux-x64.tar.gz /usr/local/java
cd /usr/local/java
sudo tar zxvf jdk-8-linux-x64.tar.gz
cd /usr/bin/
sudo ln -s  /usr/local/java/jdk1.8.0/bin/java .
sudo ln -s  /usr/local/java/jdk1.8.0/bin/javac .

#install git
sudo apt-get -y install git-core
#install ab
sudo apt-get -y install apache2-utils

cd
cat > sysctl.conf <<EOF
net.ipv4.tcp_tw_recycle = 1
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 1
net.ipv4.tcp_timestamps = 1
net.ipv4.tcp_syncookies = 0
net.ipv4.ip_local_port_range = 1024 65535
EOF
sudo sh -c "cat sysctl.conf >> /etc/sysctl.conf" 
sudo sysctl -p /etc/sysctl.conf
cat > limits.conf <<EOF
*		hard nofile	200000
*		soft nofile	200000
EOF
sudo sh -c "cat limits.conf >> /etc/security/limits.conf" 
cd

#project specific scripts
git clone https://github.com/puniverse/CascadingFailureExample.git
CascadingFailureExample/postinstall.sh