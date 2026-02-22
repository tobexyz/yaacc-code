FROM ubuntu:22.04

# Install dependencies
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    qemu-kvm \
    libvirt-daemon-system \
    libvirt-clients \
    bridge-utils \
    netcat \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Install Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator

RUN mkdir -p $ANDROID_HOME && \
    wget -O /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip && \
    unzip /tmp/tools.zip -d $ANDROID_HOME && \
    mv $ANDROID_HOME/cmdline-tools $ANDROID_HOME/cmdline-tools-temp && \
    mkdir -p $ANDROID_HOME/cmdline-tools/latest && \
    mv $ANDROID_HOME/cmdline-tools-temp/* $ANDROID_HOME/cmdline-tools/latest/

# Accept licenses and install packages
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" "platforms;android-29" "system-images;android-29;google_apis;x86_64" "emulator"

# Create AVD with cold boot configuration
RUN echo "no" | avdmanager create avd \
    --force \
    --name test_avd \
    --package "system-images;android-29;google_apis;x86_64" \
    --device "pixel_5"

# Configure AVD for headless operation with forced cold boot
RUN mkdir -p /root/.android/avd/test_avd.avd && \
    echo "hw.gpu.enabled=yes" >> /root/.android/avd/test_avd.avd/config.ini && \
    echo "hw.gpu.mode=swiftshader_indirect" >> /root/.android/avd/test_avd.avd/config.ini && \
    echo "hw.ramSize=2048" >> /root/.android/avd/test_avd.avd/config.ini && \
    echo "fastboot.forceColdBoot=yes" >> /root/.android/avd/test_avd.avd/config.ini && \
    echo "fastboot.forceFastBoot=no" >> /root/.android/avd/test_avd.avd/config.ini

WORKDIR /workspace
COPY run-tests.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/run-tests.sh

CMD ["/usr/local/bin/run-tests.sh"]
