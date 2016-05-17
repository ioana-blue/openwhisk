# Dockerfile for python action
FROM buildpack-deps:trusty

ENV DEBIAN_FRONTEND noninteractive

# Upgrade and install basic Python dependencies
RUN apt-get -y purge && \
    apt-get -y update && \
    apt-get -y install --fix-missing python2.7 python-distribute python-pip

# Install Python proxy support
RUN apt-get -y install --fix-missing python2.7-dev python-gevent python-flask
RUN apt-get clean

# install slack client
RUN pip install -U slackclient
# ssl stuff for python to avoid warnings 
RUN pip install -U pyopenssl ndg-httpsclient pyasn1
 
ENV FLASK_PROXY_PORT 8080

RUN mkdir -p /pythonAction
ADD pythonaction.py /pythonAction/

CMD ["/bin/bash", "-c", "cd pythonAction && python -u pythonaction.py"]
