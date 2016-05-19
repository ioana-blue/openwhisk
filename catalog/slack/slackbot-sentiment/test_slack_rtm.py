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

toneCredentials = {
    "url": "https://gateway.watsonplatform.net/tone-analyzer-beta/api",
    "password": "Srdrl738cdDC",
    "username": "a5dad9b0-da34-4c68-a2f3-d2bb42f23c1c"
  }

POSITIVE_TONES = ['joy', 'analytical', 'confident', ]
NEGATIVE_TONES = ['anger', 'disgust', 'fear', 'sadness', 'tentative']
INTENSITY = ['openness_big5', 'conscientiousness_big5', 'extraversion_big5', 'agreeableness_big5', 'neuroticism_big5']

EXCLUDE = {'has joined the channel'}

POSITIVE_EMOJI = [':smirk:', ':smile:', ':joy:']
NEGATIVE_EMOJI = [':cry:', ':sob:', ':rage:']
DEFAULT_EMOJI = ':no_mouth:'

user_ids = {}
channelIds = []

def populateUserIds(client):
    user_list = client.api_call("users.list")
    if 'members' in user_list:
        users = user_list['members']
        for user in users:
            name = user['name']
            id = user['id']
            user_ids[id] = name

def populateChannelIds(client):
    channelList = client.api_call("channels.list")
    print(channelList)
    if 'channels' in channelList:
        channels = channelList['channels']
        for channel in channels:
            channelIds.append(channel['id'])

def checkExclude(msg):
    for excludeStr in EXCLUDE:
        if excludeStr in msg:
            return True
    return False

def extractToneScores(toneDict):
    toneScores = {}
    if 'document_tone' in toneDict and 'tone_categories' in toneDict['document_tone']:
        categories = toneDict['document_tone']['tone_categories']
        for category in categories:
            # get each category
            if 'tones' in category:
                tones = category['tones']
                for tone in tones:
                    # get score for each tone
                    tone_id = tone['tone_id']
                    score = tone['score']
                    toneScores[tone_id] = score
    return toneScores

def analyzeToneScores(scores):
    # sum up negative ones
    negScore = 0
    for neg in NEGATIVE_TONES:
        negScore = negScore + scores[neg]
    # sum up positive ones
    posScore = 0
    for pos in POSITIVE_TONES:
        posScore = posScore + scores[pos]
    # compare the two
    emoji = POSITIVE_EMOJI
    if negScore > posScore:
        emoji = NEGATIVE_EMOJI
    # compute intensity
    intenseScore = 0
    for intense in INTENSITY:
        intenseScore = intenseScore + scores[intense]
    print(negScore, posScore, intenseScore)
    if intenseScore > 3:
        return emoji[2]
    if intenseScore > 2.5:
        return emoji[1]
    return emoji[0]

# analyze the tone of the message
def toneAnalyze(msg):
    response = requests.get(toneCredentials['url'] + "/v3/tone",
                            auth = HTTPBasicAuth(toneCredentials['username'], toneCredentials['password']),
                            headers = {'Content-type': 'application/json'},
                            params = {'text':msg, 'version':'2016-02-11'})
    if response.status_code == 200:
        # everything went smoothly
        print "TONE ANALYZER"
        #print response.text
        return extractToneScores(json.loads(response.text))
    else:
        print "status code " + str(response.status_code)
        print response.text
        return []

def processMessage(user, message, channel, timestamp):
    if (not checkExclude(message)) :
        if user != BOT_USER:
            # sc.api_call("chat.postMessage", as_user="true", channel=channel, text="well said!")
            scores = toneAnalyze(message)
            emoji = DEFAULT_EMOJI
            if scores != []:
                emoji = analyzeToneScores(scores)
                #sc.api_call("chat.postMessage", as_user="true", channel=channel, text="I feel for you " + emoji)
                sc.api_call("reactions.add", channel = channel, name = emoji.strip(':'), timestamp = timestamp )

def readChannelHistory():
    for channel in channelIds:
        # read messages from this channel since the timestamp received as parameter and this current timestamp
        hasMore = True
        while hasMore:
            response = sc.api_call("channels.history", channel = channel, oldest = lastTimestamp, latest = now, count = 1000)
            hasMore = response['has_more']
            messages = response['messages']
            for line in messages:
                if 'text' in line:
                    message = line['text']
                    user = line['user']
                    timestamp = line['ts']
                    processMessage(user, message, channel, timestamp)

def usage():
    print("The action expects as input a dictionary that contains a timestamp key.")
    print("The time is " + str(time.time()))
    exit()

# a map with (channel, timestamp) for the first message read from the channel
channelHistory = {}
if len(sys.argv) < 1:
    usage()
try:
    params = json.loads(sys.argv[1])
except Exception:
    usage()
# retrieve the timestamp from the list of arguments
if not isinstance(params, dict):
    print("not a dict")
    usage()
if 'timestamp' not in params:
    print("timestamp not found")
    usage()

lastTimestamp = params['timestamp']
now = time.time()

print(str(lastTimestamp))

sc = SlackClient(BOT_TOKEN)
if sc.rtm_connect():
    # collect user ids
    populateUserIds(sc)
    # collect channels
    populateChannelIds(sc)
    # read history from all channels
    readChannelHistory()

    while True:
        # print "one time:"
        event = sc.rtm_read()
        if not event == []:
            print(event)
        for line in event:
            if 'text' in line:
                message = line['text']
                channel = line['channel']
                user = line['user']
                timestamp = line['ts']
                processMessage(user, message, channel, timestamp)
        if event == []:
            try:
                timestamp
                #print timestamp
            except NameError:
                #print(lastTimestamp)
                lastTimestamp
            #exit()
else:
    print "Connection Failed, invalid token?"

