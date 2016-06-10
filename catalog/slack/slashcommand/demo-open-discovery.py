

# hardcode responses for the following commands
# print MS scientists
# print datasets available
# print dataset description given a dataset identifier
# print sample data from a dataset
# print analyses available for one dataset

import sys

# hardcode responses for each command

response = {
            'list MS scientists':'Ioana Baldini @ioana, Joana Maria @joana, Kush Varshney @krvarshney, Emily Ray @emilyray, Sasks Mojsilovic @datapriestess',
            'list MS datasets': '(0) ACP dataset, (1) MS dataset',
            'describe dataset': ['Accelerated Cure Project dataset contains...', 'Multiple Sclerosis National Association dataset contains... ']
            }

def main(dict):
    if 'payload' in dict:
        print(dict['payload'])
        command = dict['payload']
        if 'describe dataset' in command:
            index = int(command.split('describe dataset ')[1])
            command = 'describe dataset'
            return {"message": response[command][index]}
        return {"message": response[command]}
    else:
        print("this is dict")
        print(dict)
    return {'message':'I did not understand this command'}
