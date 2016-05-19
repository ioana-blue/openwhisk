import time
import json
import requests
import sys
from slackclient import SlackClient
from requests.auth import HTTPBasicAuth
from sqlite3.dbapi2 import TimestampFromTicks


# constants
BOT_USER = 'U14UXSBC6'
BOT_TOKEN = "xoxb-38983895414-hLboPDNDfbkdSakcBOtaZMiZ"# authentication for @sentiment bot
BOT_TOKEN = "xoxb-40830491312-Ctke28HiiE3PuhjYGYAA7dwW"


def getChannels(client):
    channelList = client.api_call("channels.list")
    #print(channelList)
    if 'channels' in channelList:
        channels = channelList['channels']
        return channels
    return []

def main(params):
    if not isinstance(params, dict):
        return {'error': 'parameter passed to function main is not a dictionary'}
    if 'bot' not in params:
        return {'error': 'bot not in the parameters dictionary'}
    if 'token' not in params:
        return {'error': 'token not in the parameters dictionary'}
    bot = params['bot']
    token = params['token']
    sc = SlackClient(token)
    if sc.rtm_connect():
        channels = getChannels(sc)
        if 'channel' in params:
            for channel in channels:
                if channel['name'] == params['channel']:
                    print channel
                    return {"success" : "information printed"}
            return {"error": "channel not found"}
        else:
            print channels
            return {"success" : "information printed"}
    else:
        return {'error': 'connection to slack not established'}

